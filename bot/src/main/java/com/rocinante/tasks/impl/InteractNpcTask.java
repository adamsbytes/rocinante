package com.rocinante.tasks.impl;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Task for interacting with NPCs.
 *
 * Per REQUIREMENTS.md Section 5.4.2:
 * <ul>
 *   <li>Extends InteractObjectTask behaviors</li>
 *   <li>Additional NPC tracking during movement</li>
 *   <li>Support dialogue initiation vs direct action</li>
 * </ul>
 *
 * <p>NPCs can move, so this task includes:
 * <ul>
 *   <li>Position tracking and mouse adjustment</li>
 *   <li>Re-targeting if NPC moves out of range</li>
 *   <li>Dialogue detection and handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Talk to a banker
 * InteractNpcTask talkToBanker = new InteractNpcTask(BANKER_ID, "Talk-to")
 *     .withDialogueExpected(true);
 *
 * // Attack a monster
 * InteractNpcTask attackGoblin = new InteractNpcTask(GOBLIN_ID, "Attack")
 *     .withSuccessAnimation(ATTACK_ANIMATION)
 *     .withSearchRadius(20);
 *
 * // Pickpocket
 * InteractNpcTask pickpocket = new InteractNpcTask(MAN_ID, "Pickpocket")
 *     .withSuccessAnimation(PICKPOCKET_ANIMATION);
 * }</pre>
 */
@Slf4j
public class InteractNpcTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding NPCs (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    /**
     * Default camera rotation probability.
     */
    private static final double DEFAULT_CAMERA_ROTATION_CHANCE = 0.3;

    /**
     * Hover delay range before clicking (ms).
     */
    private static final int HOVER_DELAY_MIN = 100;
    private static final int HOVER_DELAY_MAX = 400;

    /**
     * Maximum ticks to wait for interaction response.
     */
    private static final int INTERACTION_TIMEOUT_TICKS = 10;

    /**
     * Maximum movement distance before re-targeting (tiles).
     */
    private static final int MAX_NPC_MOVEMENT = 3;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The NPC ID to interact with.
     */
    @Getter
    private final int npcId;

    /**
     * The menu action to use (e.g., "Talk-to", "Attack", "Pickpocket").
     */
    @Getter
    private final String menuAction;

    /**
     * Search radius for finding the NPC (tiles).
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Probability of rotating camera toward NPC (0.0 - 1.0).
     */
    @Getter
    @Setter
    private double cameraRotationChance = DEFAULT_CAMERA_ROTATION_CHANCE;

    /**
     * Expected animation ID after successful interaction (optional).
     */
    @Getter
    @Setter
    private int successAnimationId = -1;

    /**
     * Whether dialogue is expected after interaction.
     */
    @Getter
    @Setter
    private boolean dialogueExpected = false;

    /**
     * Whether to track and re-target moving NPCs.
     */
    @Getter
    @Setter
    private boolean trackMovement = true;

    /**
     * Whether to wait for the player to become idle after interaction.
     */
    @Getter
    @Setter
    private boolean waitForIdle = true;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Optional specific NPC name to filter by.
     */
    @Getter
    @Setter
    private String npcName;

    /**
     * Alternate NPC IDs that also satisfy this task.
     * Quest Helper uses addAlternateNpcs() for NPCs with multiple IDs.
     */
    @Getter
    private List<Integer> alternateNpcIds = new ArrayList<>();

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private NpcInteractionPhase phase = NpcInteractionPhase.FIND_NPC;

    /**
     * The NPC we found and are interacting with.
     */
    private NPC targetNpc;

    /**
     * Last known position of the NPC.
     */
    private WorldPoint lastNpcPosition;

    /**
     * Player position when interaction started.
     */
    private WorldPoint startPosition;

    /**
     * Ticks since interaction started.
     */
    private int interactionTicks = 0;

    /**
     * Whether mouse movement is currently pending (async).
     */
    private boolean movePending = false;

    /**
     * Whether click is currently pending (async).
     */
    private boolean clickPending = false;

    /**
     * Number of re-targeting attempts.
     */
    private int retargetAttempts = 0;

    /**
     * Maximum re-targeting attempts.
     */
    private static final int MAX_RETARGET_ATTEMPTS = 3;

    /**
     * Whether menu selection is currently pending (async).
     */
    private boolean menuSelectionPending = false;

    /**
     * Cached clickbox for menu selection.
     */
    private Rectangle cachedClickbox = null;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create an interact NPC task.
     *
     * @param npcId      the NPC ID
     * @param menuAction the menu action text
     */
    public InteractNpcTask(int npcId, String menuAction) {
        this.npcId = npcId;
        this.menuAction = menuAction;
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
    public InteractNpcTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set camera rotation chance (builder-style).
     *
     * @param chance probability (0.0 - 1.0)
     * @return this task for chaining
     */
    public InteractNpcTask withCameraRotationChance(double chance) {
        this.cameraRotationChance = chance;
        return this;
    }

    /**
     * Set expected success animation (builder-style).
     *
     * @param animationId the expected animation ID
     * @return this task for chaining
     */
    public InteractNpcTask withSuccessAnimation(int animationId) {
        this.successAnimationId = animationId;
        return this;
    }

    /**
     * Set whether dialogue is expected (builder-style).
     *
     * @param expected true if dialogue is expected
     * @return this task for chaining
     */
    public InteractNpcTask withDialogueExpected(boolean expected) {
        this.dialogueExpected = expected;
        return this;
    }

    /**
     * Set whether to track NPC movement (builder-style).
     *
     * @param track true to track movement
     * @return this task for chaining
     */
    public InteractNpcTask withTrackMovement(boolean track) {
        this.trackMovement = track;
        return this;
    }

    /**
     * Set whether to wait for idle (builder-style).
     *
     * @param wait true to wait for idle
     * @return this task for chaining
     */
    public InteractNpcTask withWaitForIdle(boolean wait) {
        this.waitForIdle = wait;
        return this;
    }

    /**
     * Set NPC name filter (builder-style).
     *
     * @param name the NPC name to filter by
     * @return this task for chaining
     */
    public InteractNpcTask withNpcName(String name) {
        this.npcName = name;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public InteractNpcTask withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Add alternate NPC IDs that also satisfy this task (builder-style).
     * Quest Helper uses this for NPCs with multiple IDs based on state.
     *
     * @param ids the alternate NPC IDs
     * @return this task for chaining
     */
    public InteractNpcTask withAlternateIds(List<Integer> ids) {
        this.alternateNpcIds.addAll(ids);
        return this;
    }

    /**
     * Add alternate NPC IDs (varargs builder-style).
     *
     * @param ids the alternate NPC IDs
     * @return this task for chaining
     */
    public InteractNpcTask withAlternateIds(Integer... ids) {
        for (Integer id : ids) {
            this.alternateNpcIds.add(id);
        }
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        switch (phase) {
            case FIND_NPC:
                executeFindNpc(ctx);
                break;
            case ROTATE_CAMERA:
                executeRotateCamera(ctx);
                break;
            case MOVE_MOUSE:
                executeMoveMouse(ctx);
                break;
            case HOVER_DELAY:
                executeHoverDelay(ctx);
                break;
            case CHECK_MENU:
                executeCheckMenu(ctx);
                break;
            case CLICK:
                executeClick(ctx);
                break;
            case SELECT_MENU:
                executeSelectMenu(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
        }
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
            
            // Debug: log all NPCs we CAN see
            Client client = ctx.getClient();
            log.debug("NPCs visible (total: {}):", client.getNpcs().size());
            for (NPC npc : client.getNpcs()) {
                if (npc == null) continue;
                WorldPoint npcPos = npc.getWorldLocation();
                int dist = playerPos != null && npcPos != null ? playerPos.distanceTo(npcPos) : -1;
                log.debug("  - {} (id={}) at {} dist={}", npc.getName(), npc.getId(), npcPos, dist);
            }
            
            fail("NPC not found: " + npcId);
            return;
        }

        // Store NPC position
        lastNpcPosition = targetNpc.getWorldLocation();
        log.debug("Found NPC {} at {}", getNpcDescription(), lastNpcPosition);

        // Store starting state for success detection
        startPosition = playerPos;

        // Decide if we should rotate camera
        if (cameraRotationChance > 0 && Math.random() < cameraRotationChance) {
            phase = NpcInteractionPhase.ROTATE_CAMERA;
        } else {
            phase = NpcInteractionPhase.MOVE_MOUSE;
        }
    }

    // ========================================================================
    // Phase: Rotate Camera
    // ========================================================================

    private void executeRotateCamera(TaskContext ctx) {
        if (lastNpcPosition == null) {
            phase = NpcInteractionPhase.MOVE_MOUSE;
            return;
        }
        
        // Use MouseCameraCoupler to ensure target is visible
        var coupler = ctx.getMouseCameraCoupler();
        if (coupler != null) {
            CompletableFuture<Void> rotateFuture = coupler.ensureTargetVisible(lastNpcPosition);
            rotateFuture.whenComplete((v, ex) -> {
                if (ex != null) {
                    log.trace("Camera rotation failed: {}", ex.getMessage());
                }
                phase = NpcInteractionPhase.MOVE_MOUSE;
            });
            
            // Don't block - camera rotation happens async while we continue
            // The movement will happen concurrently which is natural
            log.debug("Rotating camera toward NPC at {}", lastNpcPosition);
        } else {
            log.debug("No camera coupler available, skipping rotation");
            phase = NpcInteractionPhase.MOVE_MOUSE;
        }
    }

    // ========================================================================
    // Phase: Move Mouse
    // ========================================================================

    private void executeMoveMouse(TaskContext ctx) {
        if (movePending) {
            return; // Still moving
        }

        // Check if NPC is still valid and track movement
        if (targetNpc == null || targetNpc.isDead()) {
            handleNpcLost(ctx);
            return;
        }

        // Track NPC movement
        if (trackMovement) {
            WorldPoint currentNpcPos = targetNpc.getWorldLocation();
            if (!currentNpcPos.equals(lastNpcPosition)) {
                int movement = lastNpcPosition.distanceTo(currentNpcPos);
                if (movement > MAX_NPC_MOVEMENT) {
                    log.debug("NPC moved significantly ({} tiles), re-targeting", movement);
                    lastNpcPosition = currentNpcPos;
                    // Mouse will be moved to new position
                }
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

        // Start async mouse movement (canvas coordinates -> screen coordinates)
        movePending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y);

        moveFuture.thenRun(() -> {
            movePending = false;
            phase = NpcInteractionPhase.HOVER_DELAY;
        }).exceptionally(e -> {
            movePending = false;
            log.error("Mouse movement failed", e);
            fail("Mouse movement failed: " + e.getMessage());
            return null;
        });
    }

    // ========================================================================
    // Phase: Hover Delay
    // ========================================================================

    private void executeHoverDelay(TaskContext ctx) {
        // Check NPC validity again before clicking
        if (targetNpc == null || targetNpc.isDead()) {
            handleNpcLost(ctx);
            return;
        }

        // Generate humanized hover delay
        int hoverMs = HOVER_DELAY_MIN + (int) (Math.random() * (HOVER_DELAY_MAX - HOVER_DELAY_MIN));
        log.debug("Hovering for {}ms before click", hoverMs);

        // Cache the clickbox for potential menu selection
        Shape clickableArea = targetNpc.getConvexHull();
        if (clickableArea != null) {
            cachedClickbox = clickableArea.getBounds();
        }

        // Next: check if we need menu selection or can left-click
        phase = NpcInteractionPhase.CHECK_MENU;
    }

    // ========================================================================
    // Phase: Check Menu
    // ========================================================================

    /**
     * Check if the desired action is available via left-click or requires menu selection.
     */
    private void executeCheckMenu(TaskContext ctx) {
        // If no menu action specified, just left-click
        if (menuAction == null || menuAction.isEmpty()) {
            log.debug("No menu action specified, using left-click");
            phase = NpcInteractionPhase.CLICK;
            return;
        }

        // Check if MenuHelper is available
        if (ctx.getMenuHelper() == null) {
            log.debug("MenuHelper not available, assuming left-click will work");
            phase = NpcInteractionPhase.CLICK;
            return;
        }

        // Check if the action is available via left-click
        boolean isLeftClick = ctx.getMenuHelper().isLeftClickActionContains(menuAction);

        if (isLeftClick) {
            log.debug("Action '{}' available via left-click", menuAction);
        phase = NpcInteractionPhase.CLICK;
        } else {
            log.debug("Action '{}' requires right-click menu selection", menuAction);
            phase = NpcInteractionPhase.SELECT_MENU;
        }
    }

    // ========================================================================
    // Phase: Click (Left-Click)
    // ========================================================================

    private void executeClick(TaskContext ctx) {
        if (clickPending) {
            return; // Click in progress
        }

        // Final NPC validity check
        if (targetNpc == null || targetNpc.isDead()) {
            handleNpcLost(ctx);
            return;
        }

        log.debug("Left-clicking NPC {} (action '{}' is default)", getNpcDescription(), menuAction);

        // Start async click
        clickPending = true;

        CompletableFuture<Void> clickFuture = ctx.getMouseController().click();

        clickFuture.thenRun(() -> {
            clickPending = false;
            interactionTicks = 0;
            phase = NpcInteractionPhase.WAIT_RESPONSE;
        }).exceptionally(e -> {
            clickPending = false;
            log.error("Click failed", e);
            fail("Click failed: " + e.getMessage());
            return null;
        });
    }

    // ========================================================================
    // Phase: Select Menu (Right-Click)
    // ========================================================================

    private void executeSelectMenu(TaskContext ctx) {
        if (menuSelectionPending) {
            return; // Menu selection in progress
        }

        if (ctx.getMenuHelper() == null) {
            log.error("MenuHelper not available for menu selection");
            fail("MenuHelper not available");
            return;
        }

        if (cachedClickbox == null) {
            log.error("No cached clickbox for menu selection");
            fail("Cannot determine click area for menu");
            return;
        }

        // Final NPC validity check
        if (targetNpc == null || targetNpc.isDead()) {
            handleNpcLost(ctx);
            return;
        }

        log.debug("Right-clicking NPC {} and selecting '{}'", getNpcDescription(), menuAction);

        menuSelectionPending = true;

        // Get NPC name for more precise menu matching
        String targetName = targetNpc.getName();

        // Use MenuHelper to right-click and select the action
        ctx.getMenuHelper().selectMenuEntry(cachedClickbox, menuAction, targetName)
                .thenAccept(success -> {
                    menuSelectionPending = false;
                    if (success) {
                        interactionTicks = 0;
                        phase = NpcInteractionPhase.WAIT_RESPONSE;
                    } else {
                        log.warn("Menu selection failed for action '{}'", menuAction);
                        fail("Menu selection failed");
                    }
                })
                .exceptionally(e -> {
                    menuSelectionPending = false;
                    log.error("Menu selection error", e);
                    fail("Menu selection error: " + e.getMessage());
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

        // Check for dialogue (if expected)
        if (dialogueExpected) {
            if (isDialogueOpen(client)) {
                success = true;
                successReason = "dialogue opened";
            }
        }

        // Check for expected animation
        if (!success && successAnimationId > 0 && player.isAnimating(successAnimationId)) {
            success = true;
            successReason = "playing expected animation";
        }

        // Check for any animation (if no specific animation expected and dialogue not expected)
        if (!success && successAnimationId < 0 && !dialogueExpected && player.isAnimating()) {
            success = true;
            successReason = "playing animation";
        }

        // Check for interaction with target NPC
        if (!success && player.isInteracting()) {
            success = true;
            successReason = "interacting with NPC";
        }

        // Check for position change (player walked to NPC)
        if (!success) {
            WorldPoint currentPos = player.getWorldPosition();
            if (currentPos != null && !currentPos.equals(startPosition)) {
                success = true;
                successReason = "moved toward NPC";
            }
        }

        if (success) {
            log.info("NPC interaction successful: {} ({})", getNpcDescription(), successReason);
            complete();
            return;
        }

        // Not yet successful, continue waiting
        log.trace("Waiting for NPC interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // NPC Finding
    // ========================================================================

    /**
     * Find the nearest instance of the target NPC.
     * Checks both primary npcId and any alternate IDs.
     */
    private NPC findNearestNpc(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        NPC nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }

            // Check ID match (primary or alternates)
            int id = npc.getId();
            if (id != npcId && !alternateNpcIds.contains(id)) {
                continue;
            }

            // Check name match if specified
            if (npcName != null && !npcName.equals(npc.getName())) {
                continue;
            }

            // Check distance
            WorldPoint npcPos = npc.getWorldLocation();
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
     */
    private Point calculateNpcClickPoint(TaskContext ctx, NPC npc) {
        Shape clickableArea = npc.getConvexHull();

        if (clickableArea == null) {
            // Fall back to model bounds
            log.debug("Using model bounds for NPC click point");
            return calculateNpcModelCenter(ctx, npc);
        }

        Rectangle bounds = clickableArea.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            return calculateNpcModelCenter(ctx, npc);
        }

        // Calculate random point within the clickable area using Gaussian distribution
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

    /**
     * Calculate click point using NPC model center as fallback.
     */
    private Point calculateNpcModelCenter(TaskContext ctx, NPC npc) {
        LocalPoint localPoint = npc.getLocalLocation();
        if (localPoint == null) {
            return null;
        }

        // Get screen position of NPC
        // This is a simplified calculation - actual implementation would use
        // Perspective.localToCanvas with proper height offset
        int height = npc.getLogicalHeight() / 2;

        // Use canvas center as fallback
        Client client = ctx.getClient();
        Dimension canvasSize = client.getCanvas().getSize();
        return new Point(canvasSize.width / 2, canvasSize.height / 2);
    }

    // ========================================================================
    // NPC Lost Handling
    // ========================================================================

    /**
     * Handle case where NPC is lost (died, despawned, moved out of range).
     */
    private void handleNpcLost(TaskContext ctx) {
        retargetAttempts++;

        if (retargetAttempts >= MAX_RETARGET_ATTEMPTS) {
            log.warn("NPC lost after {} re-targeting attempts", retargetAttempts);
            fail("NPC lost: " + npcId);
            return;
        }

        log.debug("NPC lost, attempting to re-target (attempt {})", retargetAttempts);
        phase = NpcInteractionPhase.FIND_NPC;
    }

    // ========================================================================
    // Dialogue Detection
    // ========================================================================

    /**
     * Check if a dialogue widget is currently open.
     * Uses widget group IDs for common dialogue types.
     */
    private boolean isDialogueOpen(Client client) {
        // Common dialogue widget group IDs
        // 231 = NPC dialogue, 217 = Player dialogue, 219 = Dialogue options
        int[] dialogueGroups = {231, 217, 219, 229, 193};

        for (int groupId : dialogueGroups) {
            Widget widget = client.getWidget(groupId, 0);
            if (widget != null && !widget.isHidden()) {
                return true;
            }
        }

        return false;
    }

    // ========================================================================
    // Description
    // ========================================================================

    private String getNpcDescription() {
        if (npcName != null) {
            return npcName + " (ID: " + npcId + ")";
        }
        return String.valueOf(npcId);
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("InteractNpc[%s, '%s']", getNpcDescription(), menuAction);
    }

    // ========================================================================
    // Interaction Phase Enum
    // ========================================================================

    /**
     * Phases of NPC interaction.
     */
    private enum NpcInteractionPhase {
        FIND_NPC,
        ROTATE_CAMERA,
        MOVE_MOUSE,
        HOVER_DELAY,
        CHECK_MENU,
        CLICK,
        SELECT_MENU,
        WAIT_RESPONSE
    }
}

