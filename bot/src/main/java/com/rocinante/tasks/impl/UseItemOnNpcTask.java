package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import com.rocinante.navigation.EntityFinder;
import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Task for using an inventory item on an NPC.
 *
 * <p>This task performs a two-step interaction:
 * <ol>
 *   <li>Click the item in inventory (enters "Use" cursor mode)</li>
 *   <li>Click the target NPC in the world</li>
 * </ol>
 *
 * <p>Unlike UseItemOnObjectTask, this handles:
 * <ul>
 *   <li>NPC movement tracking between item click and NPC click</li>
 *   <li>Convex hull based click calculation (NPCs use getConvexHull())</li>
 *   <li>Dialogue detection as a success indicator</li>
 *   <li>Re-targeting if NPC moves significantly</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Use fishing rod on fishing spot NPC
 * UseItemOnNpcTask fish = new UseItemOnNpcTask(ItemID.FISHING_ROD, NpcID.FISHING_SPOT);
 *
 * // Use bones on altar NPC (for prayer)
 * UseItemOnNpcTask offerBones = new UseItemOnNpcTask(ItemID.BONES, NpcID.ECTOFUNTUS)
 *     .withDialogueExpected(true);
 *
 * // Use item on quest NPC
 * UseItemOnNpcTask giveItem = new UseItemOnNpcTask(ItemID.QUEST_ITEM, NpcID.QUEST_NPC)
 *     .withDescription("Give the item to the NPC")
 *     .withDialogueExpected(true);
 * }</pre>
 */
@Slf4j
public class UseItemOnNpcTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding NPCs (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    /**
     * Maximum ticks to wait for interaction response.
     */
    private static final int INTERACTION_TIMEOUT_TICKS = 10;

    /**
     * Maximum NPC movement before re-targeting (tiles).
     */
    private static final int MAX_NPC_MOVEMENT = 3;

    /**
     * Widget IDs for dialogue detection.
     */
    private static final int WIDGET_NPC_DIALOGUE = 231;
    private static final int WIDGET_PLAYER_DIALOGUE = 217;
    private static final int WIDGET_DIALOGUE_OPTIONS = 219;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Acceptable item IDs to use.
     * Ordered by priority - first match wins.
     */
    @Getter
    private final List<Integer> itemIds;

    /**
     * The primary NPC ID to use the item on.
     */
    @Getter
    private final int npcId;

    /**
     * Alternate NPC IDs that also satisfy this task.
     */
    @Getter
    private final List<Integer> alternateNpcIds = new ArrayList<>();

    /**
     * The resolved item ID found in inventory.
     */
    private int resolvedItemId = -1;

    /**
     * The resolved NPC ID found nearby.
     */
    private int resolvedNpcId = -1;

    /**
     * Search radius for finding the NPC (tiles).
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Optional NPC name filter.
     */
    @Getter
    @Setter
    private String npcName;

    /**
     * Whether dialogue is expected after the interaction.
     */
    @Getter
    @Setter
    private boolean dialogueExpected = false;

    /**
     * Whether to track NPC movement during interaction.
     */
    @Getter
    @Setter
    private boolean trackMovement = true;

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
    private UseItemPhase phase = UseItemPhase.CLICK_ITEM;
    
    /**
     * Interaction helper for camera rotation and clickbox handling.
     */
    private InteractionHelper interactionHelper;

    /**
     * The NPC we found and are interacting with.
     */
    private NPC targetNpc;

    /**
     * Last known position of the NPC (for movement tracking).
     */
    private WorldPoint lastNpcPosition;

    /**
     * Player position when interaction started.
     */
    private WorldPoint startPosition;

    /**
     * Player animation when interaction started.
     */
    private int startAnimation;

    /**
     * Ticks since interaction started.
     */
    private int interactionTicks = 0;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Number of retarget attempts.
     */
    private int retargetAttempts = 0;

    private static final int MAX_RETARGET_ATTEMPTS = 3;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a use item on NPC task with a single item.
     *
     * @param itemId the item ID to use
     * @param npcId  the NPC ID to use the item on
     */
    public UseItemOnNpcTask(int itemId, int npcId) {
        this.itemIds = Collections.singletonList(itemId);
        this.npcId = npcId;
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on NPC task accepting any of the provided items.
     * Items are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds acceptable item IDs to use, ordered by priority
     * @param npcId   the NPC ID to use the item on
     */
    public UseItemOnNpcTask(Collection<Integer> itemIds, int npcId) {
        this.itemIds = new ArrayList<>(itemIds);
        this.npcId = npcId;
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on NPC task accepting any of the provided items on any of the provided NPCs.
     * Both items and NPCs are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds acceptable item IDs to use, ordered by priority
     * @param npcIds  acceptable NPC IDs to use the item on, ordered by priority
     */
    public UseItemOnNpcTask(Collection<Integer> itemIds, Collection<Integer> npcIds) {
        if (npcIds == null || npcIds.isEmpty()) {
            throw new IllegalArgumentException("npcIds must not be empty");
        }
        this.itemIds = new ArrayList<>(itemIds);
        List<Integer> ids = new ArrayList<>(npcIds);
        this.npcId = ids.get(0);
        if (ids.size() > 1) {
            this.alternateNpcIds.addAll(ids.subList(1, ids.size()));
        }
        this.timeout = Duration.ofSeconds(30);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set search radius (builder-style).
     *
     * @param radius search radius in tiles
     * @return this task for chaining
     */
    public UseItemOnNpcTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set NPC name filter (builder-style).
     *
     * @param name the NPC name to filter by
     * @return this task for chaining
     */
    public UseItemOnNpcTask withNpcName(String name) {
        this.npcName = name;
        return this;
    }

    /**
     * Set whether dialogue is expected (builder-style).
     *
     * @param expected true if dialogue is expected
     * @return this task for chaining
     */
    public UseItemOnNpcTask withDialogueExpected(boolean expected) {
        this.dialogueExpected = expected;
        return this;
    }

    /**
     * Set whether to track NPC movement (builder-style).
     *
     * @param track true to track movement
     * @return this task for chaining
     */
    public UseItemOnNpcTask withTrackMovement(boolean track) {
        this.trackMovement = track;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public UseItemOnNpcTask withDescription(String description) {
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

        // Find any matching item in inventory
        InventoryState inventory = ctx.getInventoryState();
        resolvedItemId = findFirstMatchingItem(inventory, itemIds);
        if (resolvedItemId == -1) {
            log.debug("No item from {} found in inventory. Inventory contains: {}",
                    itemIds, formatInventoryContents(inventory));
            return false;
        }

        log.debug("Resolved item {} from acceptable set {}", resolvedItemId, itemIds);
        return true;
    }

    @Override
    protected void resetImpl() {
        // Reset all execution state for retry
        phase = UseItemPhase.CLICK_ITEM;
        targetNpc = null;
        lastNpcPosition = null;
        startPosition = null;
        startAnimation = -1;
        interactionTicks = 0;
        operationPending = false;
        retargetAttempts = 0;
        if (interactionHelper != null) {
            interactionHelper.reset();
        }
        log.debug("UseItemOnNpcTask reset for retry");
    }

    /**
     * Find the first item ID from the list that exists in inventory.
     * Returns in priority order (first in list = highest priority).
     */
    private int findFirstMatchingItem(InventoryState inventory, List<Integer> itemIds) {
        for (int itemId : itemIds) {
            if (inventory.hasItem(itemId)) {
                return itemId;
            }
        }
        return -1;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        switch (phase) {
            case CLICK_ITEM:
                executeClickItem(ctx);
                break;
            case FIND_NPC:
                executeFindNpc(ctx);
                break;
            case MOVE_TO_NPC:
                executeMoveToNpc(ctx);
                break;
            case CLICK_NPC:
                executeClickNpc(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Click Item
    // ========================================================================

    private void executeClickItem(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        if (inventoryHelper == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            fail("InventoryClickHelper not available");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int slot = inventory.getSlotOf(resolvedItemId);

        if (slot < 0) {
            log.debug("Item {} no longer in inventory. Inventory contains: {}",
                    resolvedItemId, formatInventoryContents(inventory));
            fail("Item " + resolvedItemId + " not found in inventory");
            return;
        }

        log.debug("Clicking item {} in slot {}", resolvedItemId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use item " + resolvedItemId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        phase = UseItemPhase.FIND_NPC;
                    } else {
                        fail("Failed to click item in inventory");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click inventory item", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Find NPC
    // ========================================================================

    private void executeFindNpc(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Search for NPC
        targetNpc = findNearestNpc(ctx, playerPos);

        if (targetNpc == null) {
            log.warn("NPC {} (alternates: {}) not found within {} tiles", 
                    npcId, alternateNpcIds, searchRadius);
            logNearbyNpcs(ctx, playerPos);
            fail("NPC not found: " + npcId);
            return;
        }

        // Store NPC position for movement tracking
        lastNpcPosition = targetNpc.getWorldLocation();

        // Store starting state for success detection
        startPosition = playerPos;
        startAnimation = player.getAnimationId();

        log.debug("Found NPC {} at {}", getNpcDescription(), lastNpcPosition);
        phase = UseItemPhase.MOVE_TO_NPC;
    }

    // ========================================================================
    // Phase: Move Mouse to NPC
    // ========================================================================

    private void executeMoveToNpc(TaskContext ctx) {
        // Check if NPC is still valid
        if (targetNpc == null || targetNpc.isDead()) {
            handleNpcLost(ctx);
            return;
        }

        // Track NPC movement
        if (trackMovement) {
            WorldPoint currentNpcPos = targetNpc.getWorldLocation();
            if (currentNpcPos != null && lastNpcPosition != null && !currentNpcPos.equals(lastNpcPosition)) {
                int movement = lastNpcPosition.distanceTo(currentNpcPos);
                if (movement > MAX_NPC_MOVEMENT) {
                    log.debug("NPC moved {} tiles, updating position", movement);
                }
                lastNpcPosition = currentNpcPos;
            }
        }

        // Initialize interaction helper if needed
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
            interactionHelper.startCameraRotation(lastNpcPosition);
        }

        // Use centralized click point resolution
        InteractionHelper.ClickPointResult result = interactionHelper.getClickPointForNpc(targetNpc);
        
        Point clickPoint;
        if (result.hasPoint()) {
            clickPoint = result.point;
            log.debug("Got click point for NPC {} ({})", npcId, result.reason);
        } else if (result.shouldRotateCamera) {
            interactionHelper.startCameraRotation(lastNpcPosition);
            return;
        } else if (result.shouldWait) {
            return;
        } else {
            log.warn("Could not get click point for NPC {}: {}", npcId, result.reason);
            fail("Cannot determine click point: " + result.reason);
            return;
        }

        log.debug("Moving mouse to NPC at canvas point ({}, {})", clickPoint.x, clickPoint.y);

        operationPending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y);

        moveFuture.thenRun(() -> {
            operationPending = false;
            phase = UseItemPhase.CLICK_NPC;
        }).exceptionally(e -> {
            operationPending = false;
            log.error("Mouse movement failed", e);
            fail("Mouse movement failed: " + e.getMessage());
            return null;
        });
    }

    // ========================================================================
    // Phase: Click NPC
    // ========================================================================

    private void executeClickNpc(TaskContext ctx) {
        // Final NPC validity check
        if (targetNpc == null || targetNpc.isDead()) {
            handleNpcLost(ctx);
            return;
        }

        // If NPC has moved significantly since we started moving mouse, recalculate
        if (trackMovement) {
            WorldPoint currentNpcPos = targetNpc.getWorldLocation();
            if (currentNpcPos != null && lastNpcPosition != null) {
                int movement = lastNpcPosition.distanceTo(currentNpcPos);
                if (movement > 1) {
                    log.debug("NPC moved {} tiles while moving mouse, retargeting", movement);
                    phase = UseItemPhase.MOVE_TO_NPC;
                    return;
                }
            }
        }

        log.debug("Clicking NPC {} with item {}", getNpcDescription(), resolvedItemId);
        operationPending = true;

        // Get item name for menu matching
        String itemName = getItemName(ctx, resolvedItemId);

        // Use SafeClickExecutor for overlap-aware clicking
        ctx.getSafeClickExecutor().clickNpc(targetNpc, "Use", itemName)
                .thenAccept(success -> {
            operationPending = false;
                    if (success) {
            interactionTicks = 0;
            phase = UseItemPhase.WAIT_RESPONSE;
                    } else {
                        fail("Click failed for NPC " + getNpcDescription());
                    }
                })
                .exceptionally(e -> {
            operationPending = false;
                    log.error("Click failed", e);
            fail("Click failed: " + e.getMessage());
            return null;
        });
    }

    /**
     * Get item name from client definitions for menu matching.
     */
    private String getItemName(TaskContext ctx, int itemId) {
        try {
            ItemComposition def = ctx.getClient().getItemDefinition(itemId);
            return def != null ? def.getName() : null;
        } catch (Exception e) {
            log.debug("Could not get item name for {}", itemId);
            return null;
        }
    }

    // ========================================================================
    // Phase: Wait Response
    // ========================================================================

    private void executeWaitResponse(TaskContext ctx) {
        interactionTicks++;

        if (interactionTicks > INTERACTION_TIMEOUT_TICKS) {
            log.warn("Interaction timed out after {} ticks", INTERACTION_TIMEOUT_TICKS);
            fail("Interaction timeout - no response from game");
            return;
        }

        PlayerState player = ctx.getPlayerState();
        Client client = ctx.getClient();

        // Check for success indicators
        boolean success = false;
        String successReason = null;

        // Check for dialogue (if expected or as general success)
        if (isDialogueOpen(client)) {
            success = true;
            successReason = "dialogue opened";
        }

        // Check for any animation
        if (!success && player.isAnimating()) {
            success = true;
            successReason = "playing animation";
        }

        // Check for position change (player moved toward NPC)
        if (!success) {
            WorldPoint currentPos = player.getWorldPosition();
            if (currentPos != null && startPosition != null && !currentPos.equals(startPosition)) {
                success = true;
                successReason = "position changed";
            }
        }

        // Check for interaction target (player is interacting with NPC)
        if (!success && player.isInteracting()) {
            success = true;
            successReason = "interacting with NPC";
        }

        if (success) {
            log.info("Use item on NPC successful: item {} on NPC {} ({})", resolvedItemId, npcId, successReason);
            complete();
            return;
        }

        log.debug("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // NPC Finding
    // ========================================================================

    /**
     * Find the nearest reachable instance of the target NPC.
     * Uses NavigationService for collision-aware NPC selection.
     * NPCs behind fences/rivers are rejected.
     */
    private NPC findNearestNpc(TaskContext ctx, WorldPoint playerPos) {
        com.rocinante.navigation.NavigationService navService = ctx.getNavigationService();
        Set<Integer> npcIds = getAllNpcIds();

        Optional<com.rocinante.navigation.EntityFinder.NpcSearchResult> result = 
                navService.findNearestReachableNpc(ctx, playerPos, npcIds, searchRadius);

        if (result.isEmpty()) {
            log.debug("No reachable NPC found for IDs {} within {} tiles", npcIds, searchRadius);
            return null;
        }

        com.rocinante.navigation.EntityFinder.NpcSearchResult searchResult = result.get();
        NPC npc = searchResult.getNpc();
        resolvedNpcId = npc.getId();

        log.debug("Found reachable NPC {} at {} (path cost: {})",
                npc.getName(),
                npc.getWorldLocation(),
                searchResult.getPathCost());

        return npc;
    }

    /**
     * Get all NPC IDs this task targets (primary + alternates).
     */
    private Set<Integer> getAllNpcIds() {
        Set<Integer> ids = new HashSet<>();
        ids.add(npcId);
        ids.addAll(alternateNpcIds);
        return ids;
    }

    /**
     * Log all nearby NPCs for debugging when the target NPC is not found.
     */
    private void logNearbyNpcs(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        StringBuilder sb = new StringBuilder("Nearby NPCs within ").append(searchRadius).append(" tiles:\n");
        int count = 0;

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;

            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null) continue;

            int distance = playerPos.distanceTo(npcPos);
            if (distance <= searchRadius) {
                sb.append(String.format("  - ID=%d, Name=%s, Pos=%s, Distance=%d%s\n",
                        npc.getId(),
                        npc.getName(),
                        npcPos,
                        distance,
                        npc.isDead() ? " (DEAD)" : ""));
                count++;
            }
        }

        if (count == 0) {
            sb.append("  (none found)");
        }

        log.debug(sb.toString());
    }

    // ========================================================================
    // NPC Lost Handling
    // ========================================================================

    /**
     * Handle the case where the target NPC is no longer valid.
     */
    private void handleNpcLost(TaskContext ctx) {
        retargetAttempts++;
        if (retargetAttempts > MAX_RETARGET_ATTEMPTS) {
            log.warn("NPC lost after {} retarget attempts", MAX_RETARGET_ATTEMPTS);
            fail("Target NPC lost");
            return;
        }

        log.debug("Target NPC lost, attempting to refind (attempt {})", retargetAttempts);
        targetNpc = null;
        phase = UseItemPhase.FIND_NPC;
    }

    // ========================================================================
    // Dialogue Detection
    // ========================================================================

    /**
     * Check if a dialogue widget is currently open.
     */
    private boolean isDialogueOpen(Client client) {
        return isWidgetVisible(client, WIDGET_NPC_DIALOGUE) ||
               isWidgetVisible(client, WIDGET_PLAYER_DIALOGUE) ||
               isWidgetVisible(client, WIDGET_DIALOGUE_OPTIONS);
    }

    /**
     * Check if a widget group is visible.
     */
    private boolean isWidgetVisible(Client client, int groupId) {
        Widget widget = client.getWidget(groupId, 0);
        return widget != null && !widget.isHidden();
    }

    /**
     * Format inventory contents for debug logging.
     */
    private String formatInventoryContents(InventoryState inventory) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (net.runelite.api.Item item : inventory.getNonEmptyItems()) {
            if (!first) sb.append(", ");
            sb.append(item.getId());
            if (item.getQuantity() > 1) {
                sb.append("x").append(item.getQuantity());
            }
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    // ========================================================================
    // Description
    // ========================================================================

    private String getNpcDescription() {
        if (targetNpc != null && targetNpc.getName() != null) {
            return targetNpc.getName() + " (ID: " + npcId + ")";
        }
        return "NPC " + npcId;
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (itemIds.size() == 1) {
            return String.format("UseItemOnNpc[item=%d, npc=%d]", itemIds.get(0), npcId);
        }
        return String.format("UseItemOnNpc[items=%s, npc=%d]", itemIds, npcId);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum UseItemPhase {
        CLICK_ITEM,
        FIND_NPC,
        MOVE_TO_NPC,
        CLICK_NPC,
        WAIT_RESPONSE
    }
}

