package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
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
     * The item ID to use.
     */
    @Getter
    private final int itemId;

    /**
     * The NPC ID to use the item on.
     */
    @Getter
    private final int npcId;

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
    // Constructor
    // ========================================================================

    /**
     * Create a use item on NPC task.
     *
     * @param itemId the item ID to use
     * @param npcId  the NPC ID to use the item on
     */
    public UseItemOnNpcTask(int itemId, int npcId) {
        this.itemId = itemId;
        this.npcId = npcId;
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

        // Check if we have the item in inventory
        InventoryState inventory = ctx.getInventoryState();
        if (!inventory.hasItem(itemId)) {
            log.debug("Item {} not in inventory", itemId);
            return false;
        }

        return true;
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
        int slot = inventory.getSlotOf(itemId);

        if (slot < 0) {
            fail("Item " + itemId + " not found in inventory");
            return;
        }

        log.debug("Clicking item {} in slot {}", itemId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use item " + itemId)
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
            log.warn("NPC {} not found within {} tiles", npcId, searchRadius);
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

        // Calculate click point on NPC
        Point clickPoint = calculateNpcClickPoint(ctx, targetNpc);
        if (clickPoint == null) {
            log.warn("Could not calculate click point for NPC {}", npcId);
            fail("Cannot determine click point");
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

        log.debug("Clicking NPC {} with 'Use' cursor", getNpcDescription());

        operationPending = true;
        CompletableFuture<Void> clickFuture = ctx.getMouseController().click();

        clickFuture.thenRun(() -> {
            operationPending = false;
            interactionTicks = 0;
            phase = UseItemPhase.WAIT_RESPONSE;
        }).exceptionally(e -> {
            operationPending = false;
            log.error("Failed to click NPC", e);
            fail("Click failed: " + e.getMessage());
            return null;
        });
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
            log.info("Use item on NPC successful: item {} on NPC {} ({})", itemId, npcId, successReason);
            complete();
            return;
        }

        log.trace("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // NPC Finding
    // ========================================================================

    /**
     * Find the nearest instance of the target NPC.
     */
    private NPC findNearestNpc(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();

        NPC nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }

            // Check ID match
            if (npc.getId() != npcId) {
                continue;
            }

            // Check name filter if specified
            if (npcName != null && !npcName.isEmpty()) {
                String name = npc.getName();
                if (name == null || !name.equalsIgnoreCase(npcName)) {
                    continue;
                }
            }

            // Check distance
            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null) {
                continue;
            }

            int distance = playerPos.distanceTo(npcPos);
            if (distance <= searchRadius && distance < nearestDistance) {
                nearest = npc;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    // ========================================================================
    // Click Point Calculation
    // ========================================================================

    /**
     * Calculate the screen point to click on the NPC.
     * NPCs use getConvexHull() for their clickable area.
     */
    private Point calculateNpcClickPoint(TaskContext ctx, NPC npc) {
        Shape convexHull = npc.getConvexHull();

        if (convexHull == null) {
            log.warn("NPC has no convex hull");
            return null;
        }

        Rectangle bounds = convexHull.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("NPC has invalid bounds");
            return null;
        }

        // Calculate random point within the clickable area using Gaussian distribution
        // Per REQUIREMENTS.md Section 3.1.2: 2D Gaussian centered at 45-55%, σ = 15%
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;

        double[] offset = Randomization.staticGaussian2D(0, 0, bounds.width / 4.0, bounds.height / 4.0);
        int clickX = centerX + (int) offset[0];
        int clickY = centerY + (int) offset[1];

        // Clamp to bounds
        clickX = Math.max(bounds.x, Math.min(clickX, bounds.x + bounds.width));
        clickY = Math.max(bounds.y, Math.min(clickY, bounds.y + bounds.height));

        return new Point(clickX, clickY);
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
        return String.format("UseItemOnNpc[item=%d, npc=%d]", itemId, npcId);
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

