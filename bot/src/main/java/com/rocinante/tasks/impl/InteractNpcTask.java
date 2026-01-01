package com.rocinante.tasks.impl;

import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import com.rocinante.navigation.EntityFinder;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
public class InteractNpcTask extends com.rocinante.tasks.AbstractInteractionTask {

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
     * Expected animation IDs after successful interaction (optional).
     * Multiple IDs support actions with different animations.
     */
    @Getter
    @Setter
    private List<Integer> successAnimationIds = new ArrayList<>();

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

    /**
     * Callback to notify when the target position is determined.
     * Used by SkillTask for predictive hover exclusion.
     */
    @Setter
    private java.util.function.Consumer<WorldPoint> targetPositionCallback;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private NpcInteractionPhase phase = NpcInteractionPhase.FIND_NPC;
    
    /**
     * Interaction helper for camera rotation and clickbox handling.
     */
    // private InteractionHelper interactionHelper; // Use parent class field

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
     * Cached clickbox for menu selection.
     */
    private java.awt.Rectangle cachedClickbox = null;

    /**
     * Whether menu selection is currently pending (async).
     */
    private boolean menuSelectionPending = false;

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
    }

    /**
     * Create an interact NPC task accepting any of the provided NPC IDs.
     * NPCs are checked in order - first match wins (for priority ordering).
     *
     * @param npcIds     acceptable NPC IDs, ordered by priority
     * @param menuAction the menu action text
     */
    public InteractNpcTask(Collection<Integer> npcIds, String menuAction) {
        if (npcIds == null || npcIds.isEmpty()) {
            throw new IllegalArgumentException("npcIds must not be empty");
        }
        List<Integer> ids = new ArrayList<>(npcIds);
        this.npcId = ids.get(0);
        this.menuAction = menuAction;
        if (ids.size() > 1) {
            this.alternateNpcIds.addAll(ids.subList(1, ids.size()));
        }
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
        this.successAnimationIds = new ArrayList<>(List.of(animationId));
        return this;
    }

    /**
     * Set expected success animations (builder-style).
     * Multiple IDs support actions with different animations.
     *
     * @param animationIds the expected animation IDs
     * @return this task for chaining
     */
    public InteractNpcTask withSuccessAnimations(List<Integer> animationIds) {
        this.successAnimationIds = new ArrayList<>(animationIds);
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
    protected void resetImpl() {
        // Reset all execution state for retry
        phase = NpcInteractionPhase.FIND_NPC;
        targetNpc = null;
        lastNpcPosition = null;
        startPosition = null;
        interactionTicks = 0;
        movePending = false;
        clickPending = false;
        menuSelectionPending = false;
        retargetAttempts = 0;
        cachedClickbox = null;
        if (interactionHelper != null) {
            interactionHelper.reset();
        }
        log.debug("InteractNpcTask reset for retry");
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
            case HANDLE_OBSTACLE:
                executeHandleObstacle(ctx);
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

        // Reset interaction helper for fresh camera retry counts
        ensureInteractionHelper(ctx);
        interactionHelper.reset();

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
        
        // Notify callback of target position (used for predictive hover exclusion)
        if (targetPositionCallback != null && lastNpcPosition != null) {
            targetPositionCallback.accept(lastNpcPosition);
        }

        // Check path for obstacles (doors/gates) that need handling
        if (checkForObstacles(ctx)) {
            return;
        }

        // Store starting state for success detection
        startPosition = playerPos;

        // Decide if we should rotate camera
        if (cameraRotationChance > 0 && ctx.getRandomization().chance(cameraRotationChance)) {
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
        
        rotateCameraTo(ctx, lastNpcPosition);
        
        // Immediately proceed to mouse movement - don't wait for camera
        phase = NpcInteractionPhase.MOVE_MOUSE;
    }

    // ========================================================================
    // Phase: Handle Obstacle
    // ========================================================================

    protected void executeHandleObstacle(TaskContext ctx) {
        if (executeHandleObstacleImpl(ctx)) {
            // Still executing
            return;
        }
        // Completed or failed, move to next phase
        // If successful, we can re-find NPC. If failed, we can also try to re-find (maybe door opened anyway?)
        phase = NpcInteractionPhase.FIND_NPC;
    }

    /**
     * Check if the calculated path contains any blocking obstacles (doors/gates).
     * If so, switches phase to HANDLE_OBSTACLE and returns true.
     */
    protected boolean checkForObstacles(TaskContext ctx) {
        // Path checking for NPCs is dynamic because they move
        // We check path from player to NPC's current position
        if (startPosition != null && lastNpcPosition != null) {
            if (checkPathForObstacles(ctx, startPosition, lastNpcPosition)) {
                phase = NpcInteractionPhase.HANDLE_OBSTACLE;
                return true;
            }
        }
        return false;
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
                    
                    // Re-check path for obstacles if NPC moved significantly
                    if (checkForObstacles(ctx)) {
                        return;
                    }
                }
            }
        }

        // Initialize interaction helper if needed
        ensureInteractionHelper(ctx);

        // Use centralized click point resolution with smart waiting
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

        // Start async mouse movement
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
        int hoverMs = ctx.getRandomization().uniformRandomInt(HOVER_DELAY_MIN, HOVER_DELAY_MAX);
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

        log.debug("Clicking NPC {} (action '{}')", getNpcDescription(), menuAction);
        clickPending = true;

        // Use SafeClickExecutor for overlap-aware clicking
        ctx.getSafeClickExecutor().clickNpc(targetNpc, menuAction != null ? menuAction : "")
                .thenAccept(success -> {
            clickPending = false;
                    if (success) {
            interactionTicks = 0;
            phase = NpcInteractionPhase.WAIT_RESPONSE;
                    } else {
                        fail("Click failed for NPC " + getNpcDescription());
                    }
                })
                .exceptionally(e -> {
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

        // Check for expected animation(s)
        if (!success && !successAnimationIds.isEmpty()) {
            for (int animId : successAnimationIds) {
                if (player.isAnimating(animId)) {
                    success = true;
                    successReason = "playing expected animation " + animId;
                    break;
                }
            }
        }

        // Check for any animation (if no specific animation expected and dialogue not expected)
        if (!success && successAnimationIds.isEmpty() && !dialogueExpected && player.isAnimating()) {
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
        log.debug("Waiting for NPC interaction response (tick {})", interactionTicks);
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
        log.debug("Found reachable NPC {} at {} (path cost: {})",
                searchResult.getNpc().getName(),
                searchResult.getNpc().getWorldLocation(),
                searchResult.getPathCost());

        return searchResult.getNpc();
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
        HANDLE_OBSTACLE,
        MOVE_MOUSE,
        HOVER_DELAY,
        CHECK_MENU,
        CLICK,
        SELECT_MENU,
        WAIT_RESPONSE
    }
}

