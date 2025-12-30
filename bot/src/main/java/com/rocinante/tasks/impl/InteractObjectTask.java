package com.rocinante.tasks.impl;

import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectID;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Task for interacting with game objects.
 *
 * Per REQUIREMENTS.md Section 5.4.1:
 * <ul>
 *   <li>Locate object by ID within configurable radius (default 15 tiles)</li>
 *   <li>Camera rotation toward object (configurable probability)</li>
 *   <li>Hover briefly before clicking (100-400ms)</li>
 *   <li>Wait for player animation/position change as success indicator</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Interact with a bank booth
 * InteractObjectTask bankTask = new InteractObjectTask(BANK_BOOTH_ID, "Bank");
 *
 * // Interact with a tree for woodcutting
 * InteractObjectTask chopTask = new InteractObjectTask(TREE_ID, "Chop down")
 *     .withSearchRadius(10)
 *     .withSuccessAnimation(WOODCUTTING_ANIM);
 *
 * // Interact with a door
 * InteractObjectTask doorTask = new InteractObjectTask(DOOR_ID, "Open")
 *     .withCameraRotationChance(0.8);
 * }</pre>
 */
@Slf4j
public class InteractObjectTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding objects (tiles).
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
     * Maximum re-search attempts when object despawns during interaction.
     * Prevents infinite loops when object is contested or deleted.
     */
    private static final int MAX_DESPAWN_RETRIES = 3;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The object ID to interact with.
     */
    @Getter
    private final int objectId;

    /**
     * The menu action to use (e.g., "Bank", "Chop down", "Open").
     */
    @Getter
    private final String menuAction;

    /**
     * Search radius for finding the object (tiles).
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Probability of rotating camera toward object (0.0 - 1.0).
     */
    @Getter
    @Setter
    private double cameraRotationChance = DEFAULT_CAMERA_ROTATION_CHANCE;

    /**
     * Expected animation IDs after successful interaction (optional).
     * Used to verify interaction was successful.
     * Multiple IDs support actions with different animations (e.g., cooking on fire vs range).
     */
    @Getter
    @Setter
    private List<Integer> successAnimationIds = new ArrayList<>();

    /**
     * Whether to wait for the player to become idle after interaction.
     */
    @Getter
    @Setter
    private boolean waitForIdle = true;

    /**
     * Whether to accept position change as success indicator.
     */
    @Getter
    @Setter
    private boolean acceptPositionChange = true;

    /**
     * Whether to expect a dialogue/interface to open as the success indicator.
     * When true, the task completes when any dialogue widget is visible.
     */
    @Getter
    @Setter
    private boolean dialogueExpected = false;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Alternate object IDs that also satisfy this task.
     * Quest Helper uses addAlternateObjects() for objects with multiple IDs.
     */
    @Getter
    private List<Integer> alternateObjectIds = new java.util.ArrayList<>();

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
    protected InteractionPhase phase = InteractionPhase.FIND_OBJECT;
    
    /**
     * Object despawn/re-search retry count.
     * Tracks how many times we've had to re-find the object due to despawn.
     */
    protected int despawnRetryCount = 0;
    
    /**
     * Interaction helper for camera rotation and clickbox handling.
     * Provides centralized, human-like interaction behavior.
     */
    protected InteractionHelper interactionHelper;

    /**
     * The object we found and are interacting with.
     */
    protected TileObject targetObject;

    /**
     * World position of the target object.
     */
    protected WorldPoint targetPosition;

    /**
     * Player position when interaction started.
     */
    protected WorldPoint startPosition;

    /**
     * Player animation when interaction started.
     */
    protected int startAnimation;

    /**
     * Ticks since interaction started.
     */
    protected int interactionTicks = 0;

    /**
     * Whether mouse movement is currently pending (async).
     */
    protected boolean movePending = false;

    /**
     * Whether click is currently pending (async).
     */
    protected boolean clickPending = false;

    /**
     * Whether menu selection is currently pending (async).
     */
    protected boolean menuSelectionPending = false;

    /**
     * Cached clickbox for menu selection.
     */
    protected java.awt.Rectangle cachedClickbox = null;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create an interact object task.
     *
     * @param objectId   the game object ID
     * @param menuAction the menu action text
     */
    public InteractObjectTask(int objectId, String menuAction) {
        this.objectId = objectId;
        this.menuAction = menuAction;
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create an interact object task accepting any of the provided object IDs.
     * Objects are checked in order - first match wins (for priority ordering).
     *
     * @param objectIds  acceptable object IDs, ordered by priority
     * @param menuAction the menu action text
     */
    public InteractObjectTask(Collection<Integer> objectIds, String menuAction) {
        if (objectIds == null || objectIds.isEmpty()) {
            throw new IllegalArgumentException("objectIds must not be empty");
        }
        List<Integer> ids = new ArrayList<>(objectIds);
        this.objectId = ids.get(0);
        this.menuAction = menuAction;
        this.timeout = Duration.ofSeconds(30);
        if (ids.size() > 1) {
            this.alternateObjectIds.addAll(ids.subList(1, ids.size()));
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
    public InteractObjectTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set camera rotation chance (builder-style).
     *
     * @param chance probability (0.0 - 1.0)
     * @return this task for chaining
     */
    public InteractObjectTask withCameraRotationChance(double chance) {
        this.cameraRotationChance = chance;
        return this;
    }

    /**
     * Set expected success animation (builder-style).
     *
     * @param animationId the expected animation ID
     * @return this task for chaining
     */
    public InteractObjectTask withSuccessAnimation(int animationId) {
        this.successAnimationIds = new ArrayList<>(List.of(animationId));
        return this;
    }

    /**
     * Set expected success animations (builder-style).
     * Multiple IDs support actions with different animations (e.g., cooking on fire vs range).
     *
     * @param animationIds the expected animation IDs
     * @return this task for chaining
     */
    public InteractObjectTask withSuccessAnimations(List<Integer> animationIds) {
        this.successAnimationIds = new ArrayList<>(animationIds);
        return this;
    }

    /**
     * Set whether to wait for idle (builder-style).
     *
     * @param wait true to wait for idle
     * @return this task for chaining
     */
    public InteractObjectTask withWaitForIdle(boolean wait) {
        this.waitForIdle = wait;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public InteractObjectTask withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Add alternate object IDs that also satisfy this task (builder-style).
     * Quest Helper uses this for objects with multiple IDs based on state.
     *
     * @param ids the alternate object IDs
     * @return this task for chaining
     */
    public InteractObjectTask withAlternateIds(List<Integer> ids) {
        this.alternateObjectIds.addAll(ids);
        return this;
    }

    /**
     * Add alternate object IDs (varargs builder-style).
     *
     * @param ids the alternate object IDs
     * @return this task for chaining
     */
    public InteractObjectTask withAlternateIds(Integer... ids) {
        this.alternateObjectIds.addAll(Arrays.asList(ids));
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
        phase = InteractionPhase.FIND_OBJECT;
        despawnRetryCount = 0;
        targetObject = null;
        targetPosition = null;
        startPosition = null;
        startAnimation = -1;
        interactionTicks = 0;
        movePending = false;
        clickPending = false;
        menuSelectionPending = false;
        cachedClickbox = null;
        if (interactionHelper != null) {
            interactionHelper.reset();
        }
        log.debug("InteractObjectTask reset for retry");
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        switch (phase) {
            case FIND_OBJECT:
                executeFindObject(ctx);
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
    // Phase: Find Object
    // ========================================================================

    protected void executeFindObject(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Reset interaction helper for fresh camera retry counts
        if (interactionHelper != null) {
            interactionHelper.reset();
        }

        // Search for object
        targetObject = findNearestObject(ctx, playerPos);

        if (targetObject == null) {
            log.warn("Object {} not found within {} tiles", objectId, searchRadius);
            
            // Debug: log all nearby objects to help identify correct IDs
            logNearbyObjects(ctx, playerPos);
            
            fail("Object not found: " + objectId);
            return;
        }

        // Store object position
        targetPosition = getObjectWorldPoint(targetObject);
        log.debug("Found object {} at {}", objectId, targetPosition);
        
        // Notify callback of target position (used for predictive hover exclusion)
        if (targetPositionCallback != null && targetPosition != null) {
            targetPositionCallback.accept(targetPosition);
        }
        
        // Reset despawn retry counter on successful find
        despawnRetryCount = 0;

        // Store starting state for success detection
        startPosition = playerPos;
        startAnimation = player.getAnimationId();

        // Decide if we should rotate camera
        if (cameraRotationChance > 0 && ctx.getRandomization().chance(cameraRotationChance)) {
            phase = InteractionPhase.ROTATE_CAMERA;
        } else {
            phase = InteractionPhase.MOVE_MOUSE;
        }
    }

    // ========================================================================
    // Phase: Rotate Camera
    // ========================================================================

    protected void executeRotateCamera(TaskContext ctx) {
        if (targetPosition == null) {
            phase = InteractionPhase.MOVE_MOUSE;
            return;
        }
        
        // Initialize interaction helper if needed
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
        }
        
        // Fire-and-forget camera rotation - like a human holding an arrow key
        interactionHelper.startCameraRotation(targetPosition);
        
        // Immediately proceed to mouse movement - don't wait for camera
            phase = InteractionPhase.MOVE_MOUSE;
    }

    // ========================================================================
    // Phase: Move Mouse
    // ========================================================================

    protected void executeMoveMouse(TaskContext ctx) {
        if (movePending) {
            return; // Still moving
        }

        if (targetObject == null) {
            fail("Target object lost");
            return;
        }

        // Initialize interaction helper if needed
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
        }

        // Use centralized click point resolution with smart waiting
        InteractionHelper.ClickPointResult result = interactionHelper.getClickPointForObject(
                targetObject, targetPosition);
        
        Point clickPoint;
        if (result.hasPoint()) {
            clickPoint = result.point;
            log.debug("Got click point for object {} at ({}, {})", objectId, clickPoint.x, clickPoint.y);
        } else if (result.shouldRotateCamera) {
            interactionHelper.startCameraRotation(targetPosition);
                    return;
        } else if (result.shouldWait) {
                return;
                } else {
            log.warn("Could not get click point for object {}: {}", objectId, result.reason);
            fail("Cannot determine click point: " + result.reason);
                    return;
        }

        log.debug("Moving mouse to object at canvas point ({}, {})", clickPoint.x, clickPoint.y);

        // Start async mouse movement (canvas coordinates -> screen coordinates)
        movePending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y);

        moveFuture.thenRun(() -> {
            movePending = false;
            phase = InteractionPhase.HOVER_DELAY;
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

    protected void executeHoverDelay(TaskContext ctx) {
        // Generate humanized hover delay
        int hoverMs = ctx.getRandomization().uniformRandomInt(HOVER_DELAY_MIN, HOVER_DELAY_MAX);
        log.debug("Hovering for {}ms before click", hoverMs);

        // Cache the clickbox for potential menu selection
        if (targetObject != null) {
            Shape clickbox = targetObject.getClickbox();
            if (clickbox != null) {
                cachedClickbox = clickbox.getBounds();
            }
        }

        // Next: check if we need menu selection or can left-click
        phase = InteractionPhase.CHECK_MENU;
    }

    // ========================================================================
    // Phase: Check Menu
    // ========================================================================

    /**
     * Check if the desired action is available via left-click or requires menu selection.
     */
    protected void executeCheckMenu(TaskContext ctx) {
        // If no menu action specified, just left-click
        if (menuAction == null || menuAction.isEmpty()) {
            log.debug("No menu action specified, using left-click");
            phase = InteractionPhase.CLICK;
            return;
        }

        // Check if MenuHelper is available
        if (ctx.getMenuHelper() == null) {
            log.debug("MenuHelper not available, assuming left-click will work");
            phase = InteractionPhase.CLICK;
            return;
        }

        // Check if the action is available via left-click
        boolean isLeftClick = ctx.getMenuHelper().isLeftClickActionContains(menuAction);

        if (isLeftClick) {
            log.debug("Action '{}' available via left-click", menuAction);
        phase = InteractionPhase.CLICK;
        } else {
            log.debug("Action '{}' requires right-click menu selection", menuAction);
            phase = InteractionPhase.SELECT_MENU;
        }
    }

    // ========================================================================
    // Phase: Click (Left-Click)
    // ========================================================================

    protected void executeClick(TaskContext ctx) {
        if (clickPending) {
            return; // Click in progress
        }

        // Re-validate object still exists
        if (targetObject == null) {
            despawnRetryCount++;
            if (despawnRetryCount > MAX_DESPAWN_RETRIES) {
                log.error("Object {} despawned {} times - giving up", objectId, despawnRetryCount);
                fail("Object despawned repeatedly - may be contested or unavailable");
                return;
            }
            log.warn("Target object despawned before click (attempt {}/{}), re-searching",
                    despawnRetryCount, MAX_DESPAWN_RETRIES);
            targetPosition = null;
            cachedClickbox = null;
            phase = InteractionPhase.FIND_OBJECT;
            return;
        }

        log.debug("Clicking object {} (action '{}')", objectId, menuAction);
        clickPending = true;

        // Use SafeClickExecutor for overlap-aware clicking
        ctx.getSafeClickExecutor().clickObject(targetObject, menuAction != null ? menuAction : "")
                .thenAccept(success -> {
            clickPending = false;
                    if (success) {
            interactionTicks = 0;
            phase = InteractionPhase.WAIT_RESPONSE;
                    } else {
                        fail("Click failed for object " + objectId);
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

    protected void executeSelectMenu(TaskContext ctx) {
        if (menuSelectionPending) {
            return; // Menu selection in progress
        }

        if (ctx.getMenuHelper() == null) {
            log.error("MenuHelper not available for menu selection");
            fail("MenuHelper not available");
            return;
        }

        // Recalculate fresh clickbox in case camera rotated
        if (targetObject != null) {
            Shape clickable = targetObject.getClickbox();
            if (clickable != null) {
                cachedClickbox = clickable.getBounds();
            } else {
                despawnRetryCount++;
                if (despawnRetryCount > MAX_DESPAWN_RETRIES) {
                    log.error("Object {} despawned {} times - giving up", objectId, despawnRetryCount);
                    fail("Object despawned repeatedly - may be contested or unavailable");
                    return;
                }
                log.warn("Target object despawned before menu selection (attempt {}/{}), re-searching",
                        despawnRetryCount, MAX_DESPAWN_RETRIES);
                targetObject = null;
                targetPosition = null;
                cachedClickbox = null;
                phase = InteractionPhase.FIND_OBJECT;
                return;
            }
        }

        if (cachedClickbox == null) {
            log.error("No cached clickbox for menu selection");
            fail("Cannot determine click area for menu");
            return;
        }

        log.debug("Right-clicking object {} and selecting '{}'", objectId, menuAction);

        menuSelectionPending = true;

        // Use MenuHelper to right-click and select the action
        ctx.getMenuHelper().selectMenuEntry(cachedClickbox, menuAction)
                .thenAccept(success -> {
                    menuSelectionPending = false;
                    if (success) {
                        interactionTicks = 0;
                        phase = InteractionPhase.WAIT_RESPONSE;
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

    protected void executeWaitResponse(TaskContext ctx) {
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

        // Check for dialogue/interface (if expected) - check first as it's most reliable for objects like poll booths
        if (!success && dialogueExpected) {
            if (isDialogueOpen(client)) {
                success = true;
                successReason = "dialogue/interface opened";
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

        // Check for position change
        if (!success && acceptPositionChange) {
            WorldPoint currentPos = player.getWorldPosition();
            if (currentPos != null && !currentPos.equals(startPosition)) {
                success = true;
                successReason = "position changed";
            }
        }

        // Check for interaction target change
        if (!success && player.isInteracting()) {
            success = true;
            successReason = "interacting with entity";
        }

        if (success) {
            log.info("Object interaction successful: {} ({})", objectId, successReason);
            complete();
            return;
        }

        // Not yet successful, continue waiting
        log.trace("Waiting for interaction response (tick {})", interactionTicks);
    }

    /**
     * Check if any dialogue or interface widget is currently open.
     * Covers common dialogue types including MESBOX.
     */
    private boolean isDialogueOpen(Client client) {
        // Common dialogue/interface widget group IDs:
        // 231 = NPC dialogue
        // 217 = Player dialogue  
        // 219 = Dialogue options
        // 229 = MESBOX (info messages like poll booth)
        // 193 = Sprite dialogue
        // 162 = Polls interface
        int[] dialogueGroups = {231, 217, 219, 229, 193, 162};

        for (int groupId : dialogueGroups) {
            Widget widget = client.getWidget(groupId, 0);
            if (widget != null && !widget.isHidden()) {
                log.debug("Dialogue/interface widget {} is open", groupId);
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // Object Finding
    // ========================================================================

    /**
     * Find the nearest instance of the target object.
     */
    protected TileObject findNearestObject(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        int playerPlane = playerPos.getPlane();
        TileObject nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        // Search tiles within radius
        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

                // Check all object types on this tile
                TileObject obj = findObjectOnTile(tile);
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
     * Log all nearby objects for debugging when target object isn't found.
     */
    private void logNearbyObjects(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();
        
        log.debug("Objects within {} tiles:", searchRadius);
        int count = 0;
        
        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }
                
                // Check game objects
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject obj : gameObjects) {
                        if (obj != null) {
                            WorldPoint objPos = obj.getWorldLocation();
                            int dist = playerPos.distanceTo(objPos);
                            if (dist <= searchRadius) {
                                log.debug("  - GameObject (id={}) at {} dist={}", 
                                    obj.getId(), objPos, dist);
                                count++;
                            }
                        }
                    }
                }
                
                // Check wall objects
                WallObject wallObj = tile.getWallObject();
                if (wallObj != null) {
                    WorldPoint objPos = wallObj.getWorldLocation();
                    int dist = playerPos.distanceTo(objPos);
                    if (dist <= searchRadius) {
                        log.debug("  - WallObject (id={}) at {} dist={}", 
                            wallObj.getId(), objPos, dist);
                        count++;
                    }
                }
                
                // Check ground objects
                GroundObject groundObj = tile.getGroundObject();
                if (groundObj != null) {
                    WorldPoint objPos = groundObj.getWorldLocation();
                    int dist = playerPos.distanceTo(objPos);
                    if (dist <= searchRadius) {
                        log.debug("  - GroundObject (id={}) at {} dist={}", 
                            groundObj.getId(), objPos, dist);
                        count++;
                    }
                }
                
                // Check decorative objects
                DecorativeObject decObj = tile.getDecorativeObject();
                if (decObj != null) {
                    WorldPoint objPos = decObj.getWorldLocation();
                    int dist = playerPos.distanceTo(objPos);
                    if (dist <= searchRadius) {
                        log.debug("  - DecorativeObject (id={}) at {} dist={}", 
                            decObj.getId(), objPos, dist);
                        count++;
                    }
                }
            }
        }
        
        log.debug("Total nearby objects: {}", count);
    }

    /**
     * Find the target object on a specific tile.
     * Checks both primary objectId and any alternate IDs.
     */
    private TileObject findObjectOnTile(Tile tile) {
        // Check game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && matchesObjectId(obj.getId())) {
                    return obj;
                }
            }
        }

        // Check wall object
        WallObject wallObject = tile.getWallObject();
        if (wallObject != null && matchesObjectId(wallObject.getId())) {
            return wallObject;
        }

        // Check decorative object
        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null && matchesObjectId(decorativeObject.getId())) {
            return decorativeObject;
        }

        // Check ground object
        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null && matchesObjectId(groundObject.getId())) {
            return groundObject;
        }

        return null;
    }

    /**
     * Check if an ID matches the primary object ID or any alternate.
     */
    private boolean matchesObjectId(int id) {
        return id == objectId || alternateObjectIds.contains(id);
    }

    /**
     * Get the WorldPoint of a TileObject.
     */
    protected WorldPoint getObjectWorldPoint(TileObject obj) {
        if (obj instanceof GameObject) {
            return ((GameObject) obj).getWorldLocation();
        }
        // For other TileObject types (WallObject, GroundObject, DecorativeObject),
        // we use the tile's world location since they don't have getWorldLocation()
        if (obj instanceof WallObject) {
            return ((WallObject) obj).getWorldLocation();
        }
        if (obj instanceof GroundObject) {
            return ((GroundObject) obj).getWorldLocation();
        }
        if (obj instanceof DecorativeObject) {
            return ((DecorativeObject) obj).getWorldLocation();
        }
        // Fallback - shouldn't happen but just in case
        return null;
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("InteractObject[%d, '%s']", objectId, menuAction);
    }

    // ========================================================================
    // Execution Phase Enum
    // ========================================================================

    /**
     * Phases of object interaction.
     */
    protected enum InteractionPhase {
        FIND_OBJECT,
        ROTATE_CAMERA,
        MOVE_MOUSE,
        HOVER_DELAY,
        CHECK_MENU,
        CLICK,
        SELECT_MENU,
        WAIT_RESPONSE
    }
}

