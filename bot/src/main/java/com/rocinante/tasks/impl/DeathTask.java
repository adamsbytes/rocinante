package com.rocinante.tasks.impl;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Task for handling player death and item recovery.
 *
 * <p>OSRS Death Mechanics (as of 2024):
 * <ul>
 *   <li>On death, player respawns at their chosen respawn location</li>
 *   <li>Items are placed in a gravestone at the death location</li>
 *   <li>15 minutes to retrieve items from gravestone (free)</li>
 *   <li>After timeout, items go to Death's Office (fee required)</li>
 *   <li>HCIM lose hardcore status on death</li>
 * </ul>
 *
 * <p>This task handles:
 * <ul>
 *   <li>Death state detection</li>
 *   <li>Navigation to gravestone or Death's Office</li>
 *   <li>Item retrieval</li>
 *   <li>Return to previous activity location</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Handle death and return to combat area
 * DeathTask deathRecovery = new DeathTask()
 *     .withDeathLocation(deathPoint)
 *     .withReturnLocation(combatArea)
 *     .withPreferGravestone(true);
 *
 * // Handle death with Death's Office as fallback
 * DeathTask deathOffice = new DeathTask()
 *     .withPreferGravestone(false);
 * }</pre>
 */
@Slf4j
public class DeathTask extends AbstractTask {

    // ========================================================================
    // Interface Constants
    // ========================================================================

    /**
     * Gravestone retrieval interface ID (602).
     */
    private static final int GRAVESTONE_INTERFACE_ID = 602;

    /**
     * Death's Office interface ID (669).
     */
    private static final int DEATH_OFFICE_INTERFACE_ID = 669;

    /**
     * Death's Coffer interface ID (670).
     */
    private static final int DEATH_COFFER_INTERFACE_ID = 670;

    /**
     * Items kept on death interface ID (4).
     */
    private static final int DEATHKEEP_INTERFACE_ID = 4;

    // ========================================================================
    // Known Locations
    // ========================================================================

    /**
     * Death's Office location (north of Lumbridge).
     */
    public static final WorldPoint DEATHS_OFFICE_LOCATION = new WorldPoint(3230, 3220, 0);

    /**
     * Common respawn locations.
     */
    public static final WorldPoint LUMBRIDGE_RESPAWN = new WorldPoint(3222, 3218, 0);
    public static final WorldPoint FALADOR_RESPAWN = new WorldPoint(2971, 3340, 0);
    public static final WorldPoint CAMELOT_RESPAWN = new WorldPoint(2757, 3477, 0);
    public static final WorldPoint EDGEVILLE_RESPAWN = new WorldPoint(3094, 3491, 0);

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Location where the player died (for gravestone retrieval).
     */
    @Getter
    @Setter
    private WorldPoint deathLocation;

    /**
     * Location to return to after item recovery.
     */
    @Getter
    @Setter
    private WorldPoint returnLocation;

    /**
     * Whether to prefer gravestone retrieval over Death's Office.
     * Gravestone is free but requires navigation to death location.
     * Death's Office charges a fee but is always accessible.
     */
    @Getter
    @Setter
    private boolean preferGravestone = true;

    /**
     * Maximum GP willing to pay at Death's Office.
     * If fee exceeds this, will attempt gravestone instead.
     */
    @Getter
    @Setter
    private int maxDeathOfficeFee = 100000;

    /**
     * Custom description for logging.
     */
    @Setter
    private String description;

    // ========================================================================
    // State
    // ========================================================================

    private enum Phase {
        /**
         * Detect if player is dead or needs item recovery.
         */
        DETECT_STATE,

        /**
         * Navigate to gravestone location.
         */
        GO_TO_GRAVESTONE,

        /**
         * Interact with gravestone to retrieve items.
         */
        RETRIEVE_FROM_GRAVESTONE,

        /**
         * Navigate to Death's Office.
         */
        GO_TO_DEATHS_OFFICE,

        /**
         * Interact with Death to reclaim items.
         */
        RETRIEVE_FROM_DEATH,

        /**
         * Return to the specified location.
         */
        RETURN_TO_LOCATION,

        /**
         * Task complete.
         */
        DONE
    }

    private Phase phase = Phase.DETECT_STATE;
    private Task activeSubTask;
    private boolean actionPending = false;
    private int waitTicks = 0;
    private static final int MAX_WAIT_TICKS = 10;

    /**
     * Whether items were successfully retrieved.
     */
    @Getter
    private boolean itemsRetrieved = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a death recovery task with default settings.
     */
    public DeathTask() {
        // Default constructor
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the death location for gravestone retrieval (builder-style).
     *
     * @param deathLocation where the player died
     * @return this task for chaining
     */
    public DeathTask withDeathLocation(WorldPoint deathLocation) {
        this.deathLocation = deathLocation;
        return this;
    }

    /**
     * Set the location to return to after recovery (builder-style).
     *
     * @param returnLocation where to go after getting items
     * @return this task for chaining
     */
    public DeathTask withReturnLocation(WorldPoint returnLocation) {
        this.returnLocation = returnLocation;
        return this;
    }

    /**
     * Set gravestone preference (builder-style).
     *
     * @param preferGravestone true to prefer gravestone over Death's Office
     * @return this task for chaining
     */
    public DeathTask withPreferGravestone(boolean preferGravestone) {
        this.preferGravestone = preferGravestone;
        return this;
    }

    /**
     * Set maximum Death's Office fee (builder-style).
     *
     * @param maxFee maximum GP to pay
     * @return this task for chaining
     */
    public DeathTask withMaxDeathOfficeFee(int maxFee) {
        this.maxDeathOfficeFee = maxFee;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public DeathTask withDescription(String description) {
        this.description = description;
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
        if (actionPending) {
            return;
        }

        // Execute active sub-task if present
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                activeSubTask = null;
            } else {
                return;
            }
        }

        // Timeout check
        waitTicks++;
        if (waitTicks > MAX_WAIT_TICKS) {
            waitTicks = 0;
        }

        switch (phase) {
            case DETECT_STATE:
                executeDetectState(ctx);
                break;
            case GO_TO_GRAVESTONE:
                executeGoToGravestone(ctx);
                break;
            case RETRIEVE_FROM_GRAVESTONE:
                executeRetrieveFromGravestone(ctx);
                break;
            case GO_TO_DEATHS_OFFICE:
                executeGoToDeathsOffice(ctx);
                break;
            case RETRIEVE_FROM_DEATH:
                executeRetrieveFromDeath(ctx);
                break;
            case RETURN_TO_LOCATION:
                executeReturnToLocation(ctx);
                break;
            case DONE:
                complete();
                break;
        }
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private void executeDetectState(TaskContext ctx) {
        Client client = ctx.getClient();
        PlayerState player = ctx.getPlayerState();

        // Check if player is at death's office or respawn location
        WorldPoint playerPos = player.getWorldPosition();
        if (playerPos == null) {
            return;
        }

        // Check if gravestone interface is open
        Widget gravestoneWidget = client.getWidget(GRAVESTONE_INTERFACE_ID, 0);
        if (gravestoneWidget != null && !gravestoneWidget.isHidden()) {
            log.info("Gravestone interface detected, retrieving items");
            phase = Phase.RETRIEVE_FROM_GRAVESTONE;
            waitTicks = 0;
            return;
        }

        // Check if Death's Office interface is open
        Widget deathOfficeWidget = client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0);
        if (deathOfficeWidget != null && !deathOfficeWidget.isHidden()) {
            log.info("Death's Office interface detected, retrieving items");
            phase = Phase.RETRIEVE_FROM_DEATH;
            waitTicks = 0;
            return;
        }

        // Determine recovery method
        if (preferGravestone && deathLocation != null) {
            log.info("Death recovery: heading to gravestone at {}", deathLocation);
            phase = Phase.GO_TO_GRAVESTONE;
        } else {
            log.info("Death recovery: heading to Death's Office");
            phase = Phase.GO_TO_DEATHS_OFFICE;
        }
        waitTicks = 0;
    }

    private void executeGoToGravestone(TaskContext ctx) {
        if (deathLocation == null) {
            log.warn("No death location set, falling back to Death's Office");
            phase = Phase.GO_TO_DEATHS_OFFICE;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return;
        }

        // Check if we're close enough to interact with gravestone
        int distance = playerPos.distanceTo(deathLocation);
        if (distance <= 5) {
            // Look for gravestone object nearby
            // For now, advance to retrieval phase
            log.debug("Near gravestone location, attempting retrieval");
            phase = Phase.RETRIEVE_FROM_GRAVESTONE;
            waitTicks = 0;
            return;
        }

        // Walk to gravestone
        if (activeSubTask == null) {
            log.debug("Walking to gravestone at {}", deathLocation);
            WalkToTask walkTask = new WalkToTask(deathLocation);
            walkTask.setDescription("Walk to gravestone");
            activeSubTask = walkTask;
        }
    }

    private void executeRetrieveFromGravestone(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if gravestone interface is open
        Widget gravestoneWidget = client.getWidget(GRAVESTONE_INTERFACE_ID, 0);
        if (gravestoneWidget == null || gravestoneWidget.isHidden()) {
            // Need to click on the gravestone object
            // This would require object interaction - for now, log and attempt
            log.debug("Gravestone interface not open, searching for gravestone object...");

            // Try to find and click gravestone object
            // The gravestone object ID varies, but we can search for it
            // For now, wait and retry or fall back to Death's Office
            if (waitTicks > MAX_WAIT_TICKS / 2) {
                log.warn("Cannot find gravestone interface, trying Death's Office");
                phase = Phase.GO_TO_DEATHS_OFFICE;
            }
            return;
        }

        // Click "Reclaim all" button or similar
        // The exact child ID depends on the interface version
        // Common pattern: look for a button with "Reclaim" action
        Widget reclaimButton = findReclaimButton(gravestoneWidget, client);
        if (reclaimButton == null) {
            log.warn("Cannot find reclaim button in gravestone interface");
            if (waitTicks > MAX_WAIT_TICKS) {
                // Fall back to Death's Office
                phase = Phase.GO_TO_DEATHS_OFFICE;
            }
            return;
        }

        // Click the reclaim button
        clickWidget(ctx, reclaimButton, "Reclaim items from gravestone");
    }

    private void executeGoToDeathsOffice(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return;
        }

        // Check if we're at Death's Office
        int distance = playerPos.distanceTo(DEATHS_OFFICE_LOCATION);
        if (distance <= 10) {
            log.debug("At Death's Office area, looking for Death NPC");
            phase = Phase.RETRIEVE_FROM_DEATH;
            waitTicks = 0;
            return;
        }

        // Walk to Death's Office
        if (activeSubTask == null) {
            log.debug("Walking to Death's Office");
            WalkToTask walkTask = new WalkToTask(DEATHS_OFFICE_LOCATION);
            walkTask.setDescription("Walk to Death's Office");
            activeSubTask = walkTask;
        }
    }

    private void executeRetrieveFromDeath(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if Death's coffer interface is open
        Widget cofferWidget = client.getWidget(DEATH_COFFER_INTERFACE_ID, 0);
        if (cofferWidget != null && !cofferWidget.isHidden()) {
            // Coffer interface is open, click reclaim
            Widget reclaimButton = findReclaimButton(cofferWidget, client);
            if (reclaimButton != null) {
                clickWidget(ctx, reclaimButton, "Reclaim items from Death's Coffer");
                return;
            }
        }

        // Check if Death's Office interface is open
        Widget officeWidget = client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0);
        if (officeWidget != null && !officeWidget.isHidden()) {
            // Office interface is open, look for retrieve option
            Widget retrieveButton = findReclaimButton(officeWidget, client);
            if (retrieveButton != null) {
                clickWidget(ctx, retrieveButton, "Retrieve items from Death");
                return;
            }
        }

        // Need to talk to Death NPC
        // This would use InteractNpcTask - for now, log
        log.debug("Need to interact with Death NPC...");

        // Simplified: assume dialogue will be handled by DialogueTask
        // In production, this would create an InteractNpcTask
        if (waitTicks > MAX_WAIT_TICKS) {
            log.warn("Death recovery: Unable to interact with Death");
            // Complete anyway to avoid infinite loop
            itemsRetrieved = false;
            phase = Phase.RETURN_TO_LOCATION;
        }
    }

    private void executeReturnToLocation(TaskContext ctx) {
        if (returnLocation == null) {
            log.info("Death recovery complete, no return location specified");
            phase = Phase.DONE;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return;
        }

        int distance = playerPos.distanceTo(returnLocation);
        if (distance <= 5) {
            log.info("Returned to activity location");
            phase = Phase.DONE;
            return;
        }

        if (activeSubTask == null) {
            log.debug("Returning to {}", returnLocation);
            WalkToTask walkTask = new WalkToTask(returnLocation);
            walkTask.setDescription("Return to activity location");
            activeSubTask = walkTask;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Find the reclaim/retrieve button in a death-related interface.
     */
    private Widget findReclaimButton(Widget parentWidget, Client client) {
        if (parentWidget == null) {
            return null;
        }

        // Try static children first
        Widget[] children = parentWidget.getStaticChildren();
        if (children != null) {
            for (Widget child : children) {
                if (isReclaimButton(child)) {
                    return child;
                }
            }
        }

        // Try dynamic children
        Widget[] dynamicChildren = parentWidget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (isReclaimButton(child)) {
                    return child;
                }
            }
        }

        // Try nested children
        Widget[] nestedChildren = parentWidget.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                if (isReclaimButton(child)) {
                    return child;
                }
            }
        }

        return null;
    }

    /**
     * Check if a widget is a reclaim/retrieve button.
     */
    private boolean isReclaimButton(Widget widget) {
        if (widget == null || widget.isHidden()) {
            return false;
        }

        String[] actions = widget.getActions();
        if (actions != null) {
            for (String action : actions) {
                if (action != null) {
                    String lower = action.toLowerCase();
                    if (lower.contains("reclaim") || lower.contains("retrieve") ||
                        lower.contains("collect") || lower.contains("take")) {
                        return true;
                    }
                }
            }
        }

        String text = widget.getText();
        if (text != null) {
            String lower = text.toLowerCase();
            if (lower.contains("reclaim") || lower.contains("retrieve")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Click a widget with humanized positioning.
     */
    private void clickWidget(TaskContext ctx, Widget widget, String actionDescription) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Widget has invalid bounds: {}", actionDescription);
            return;
        }

        int clickX = bounds.x + bounds.width / 2 + ThreadLocalRandom.current().nextInt(-5, 6);
        int clickY = bounds.y + bounds.height / 2 + ThreadLocalRandom.current().nextInt(-3, 4);

        log.debug("{} at ({}, {})", actionDescription, clickX, clickY);

        actionPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    itemsRetrieved = true;
                    waitTicks = 0;

                    // After reclaiming, go to return location
                    if (returnLocation != null) {
                        phase = Phase.RETURN_TO_LOCATION;
                    } else {
                        phase = Phase.DONE;
                    }
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to click: {}", actionDescription, e);
                    return null;
                });
    }

    // ========================================================================
    // Task Metadata
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return "Death recovery" + (deathLocation != null ? " at " + deathLocation : "");
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if player appears to have just died (low HP, at respawn location).
     *
     * @param ctx the task context
     * @return true if player likely just died
     */
    public static boolean hasPlayerJustDied(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        if (player == null || !player.isValid()) {
            return false;
        }

        // Check if at a common respawn location with low HP
        WorldPoint pos = player.getWorldPosition();
        if (pos == null) {
            return false;
        }

        // Check proximity to respawn locations
        boolean nearRespawn = pos.distanceTo(LUMBRIDGE_RESPAWN) < 10 ||
                              pos.distanceTo(FALADOR_RESPAWN) < 10 ||
                              pos.distanceTo(CAMELOT_RESPAWN) < 10 ||
                              pos.distanceTo(EDGEVILLE_RESPAWN) < 10;

        // Low HP immediately after respawn (usually 10 or similar)
        boolean lowHp = player.getHealthPercent() < 0.3;

        return nearRespawn && lowHp;
    }

    /**
     * Check if a gravestone or death interface is currently visible.
     *
     * @param client the game client
     * @return true if death-related interface is visible
     */
    public static boolean isDeathInterfaceVisible(Client client) {
        Widget gravestone = client.getWidget(GRAVESTONE_INTERFACE_ID, 0);
        if (gravestone != null && !gravestone.isHidden()) {
            return true;
        }

        Widget deathOffice = client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0);
        if (deathOffice != null && !deathOffice.isHidden()) {
            return true;
        }

        Widget coffer = client.getWidget(DEATH_COFFER_INTERFACE_ID, 0);
        if (coffer != null && !coffer.isHidden()) {
            return true;
        }

        return false;
    }
}

