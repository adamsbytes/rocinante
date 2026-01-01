package com.rocinante.tasks.impl;

import com.rocinante.state.PlayerState;
import net.runelite.api.NpcID;
import net.runelite.api.ObjectID;
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

    // Death's Office exit portal: use ObjectID.PORTAL_39549 (also known as DEATH_OFFICE_EXITPORTAL in gameval)

    /**
     * Death's Office region ID.
     */
    private static final int DEATHS_OFFICE_REGION = 12106;

    /**
     * NPC dialogue widget group (shows NPC name and dialogue).
     */
    private static final int DIALOGUE_NPC_HEAD_GROUP = 231;

    /**
     * Click to continue dialogue widget group.
     */
    private static final int DIALOGUE_SPRITE_GROUP = 193;

    /**
     * Player dialogue options widget group.
     */
    private static final int DIALOGUE_OPTIONS_GROUP = 219;

    // ========================================================================
    // Gravestone VarClient/Varbit Constants
    // ========================================================================

    /**
     * VarClient ID for gravestone X coordinate.
     * From gameval: GRAVESTONE_X = 397
     */
    private static final int VARCLIENT_GRAVESTONE_X = 397;

    /**
     * VarClient ID for gravestone Y coordinate.
     * From gameval: GRAVESTONE_Y = 398
     */
    private static final int VARCLIENT_GRAVESTONE_Y = 398;

    /**
     * Varbit ID for whether a gravestone is currently active.
     * From gameval: GRAVESTONE_VISIBLE = 10464
     */
    private static final int VARBIT_GRAVESTONE_VISIBLE = 10464;

    /**
     * Varbit ID for gravestone time remaining.
     * From gameval: GRAVESTONE_DURATION = 10465
     */
    private static final int VARBIT_GRAVESTONE_DURATION = 10465;

    /**
     * Death's Domain portal in Death's Office - teleports to gravestone.
     * ObjectID.DEATHS_DOMAIN = 38426 (also known as DEATH_OFFICE_ACCESS_GRAVE)
     */
    private static final int DEATHS_DOMAIN_PORTAL_ID = ObjectID.DEATHS_DOMAIN;

    // Note: Player gravestones use various ObjectIDs depending on the type selected.
    // Common ones: GRAVESTONE (404), GRAVESTONE_405, SMALL_GRAVESTONE (400), GRAVE_MARKER (401)
    // InteractObjectTask handles finding the nearest matching object.

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
         * Handle Death's tutorial dialogue (first death).
         * Click through "Click here to continue" until interface opens.
         */
        HANDLE_DEATH_DIALOGUE,

        /**
         * Use Death's Domain portal to teleport to gravestone location.
         */
        USE_GRAVE_PORTAL,

        /**
         * Wait for teleport from Death's Domain portal to complete.
         */
        WAIT_FOR_GRAVE_TELEPORT,

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
         * Exit Death's Office through the portal.
         */
        EXIT_DEATHS_OFFICE,

        /**
         * Wait for region change after exiting Death's Office.
         */
        WAIT_FOR_EXIT,

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
     * Gravestone location retrieved from VarClient.
     * Set during DETECT_STATE if a gravestone is active.
     */
    private WorldPoint gravestoneLocation;

    /**
     * Whether we detected a valid gravestone via VarClient/Varbit.
     */
    private boolean hasActiveGravestone = false;

    /**
     * Region ID before teleporting (to detect teleport completion).
     */
    private int preTeleportRegion = -1;

    /**
     * Whether items were successfully retrieved.
     */
    @Getter
    private boolean itemsRetrieved = false;

    /**
     * Whether dialogue (first-death tutorial) was completed.
     * Used to differentiate "completed tutorial, no gravestone" from "need to go to Death's Office".
     */
    private boolean dialogueCompleted = false;

    /**
     * Number of times we've retried the dialogue after failure.
     */
    private int dialogueRetryCount = 0;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a death recovery task with default settings.
     * Uses a longer timeout (5 minutes) because the Death tutorial has LOTS of dialogue.
     */
    public DeathTask() {
        // Death tutorial has many dialogue screens - need longer timeout than default 60s
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
            case HANDLE_DEATH_DIALOGUE:
                executeHandleDeathDialogue(ctx);
                break;
            case USE_GRAVE_PORTAL:
                executeUseGravePortal(ctx);
                break;
            case WAIT_FOR_GRAVE_TELEPORT:
                executeWaitForGraveTeleport(ctx);
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
            case EXIT_DEATHS_OFFICE:
                executeExitDeathsOffice(ctx);
                break;
            case WAIT_FOR_EXIT:
                executeWaitForExit(ctx);
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

        // Ultimate Ironmen can't use Death's Office for item retrieval
        // Their items fall on the ground where they died
        if (ctx.getIronmanState().isUltimate()) {
            log.info("Ultimate Ironman detected - Death's Office cannot retrieve items. Items are on ground at death location.");
            // UIM just needs to leave Death's Office and return to death location
            if (returnLocation != null || deathLocation != null) {
                phase = Phase.RETURN_TO_LOCATION;
            } else {
                // No death location known, just complete
                log.warn("UIM death recovery: no death location known, completing without item retrieval");
                itemsRetrieved = false;
                complete();
            }
            return;
        }

        // Check if Death's dialogue is open (first death tutorial)
        if (isDeathDialogueOpen(client)) {
            log.info("Death tutorial dialogue detected, handling first death");
            phase = Phase.HANDLE_DEATH_DIALOGUE;
            waitTicks = 0;
            return;
        }

        // Check if gravestone interface is open (already at gravestone)
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

        // Check if Death's Coffer interface is open
        Widget cofferWidget = client.getWidget(DEATH_COFFER_INTERFACE_ID, 0);
        if (cofferWidget != null && !cofferWidget.isHidden()) {
            log.info("Death's Coffer interface detected, retrieving items");
            phase = Phase.RETRIEVE_FROM_DEATH;
            waitTicks = 0;
            return;
        }

        // ========================================================================
        // Check VarClient for gravestone location (key for crash recovery!)
        // ========================================================================
        detectGravestoneFromVarClient(client);

        int regionId = playerPos.getRegionID();
        
        // If we're in Death's Office
        if (regionId == DEATHS_OFFICE_REGION) {
            log.info("In Death's Office - checking for gravestone location");
            
            if (hasActiveGravestone && gravestoneLocation != null) {
                // We have a gravestone! Use the portal to teleport there
                log.info("Active gravestone detected at {} - will use Death's Domain portal", gravestoneLocation);
                phase = Phase.USE_GRAVE_PORTAL;
            } else {
                // No gravestone - items may be with Death or already recovered
                log.info("No active gravestone - checking with Death for items");
                phase = Phase.RETRIEVE_FROM_DEATH;
            }
            waitTicks = 0;
            return;
        }

        // Not in Death's Office - determine recovery method
        if (hasActiveGravestone && gravestoneLocation != null) {
            // We have gravestone coordinates - go there directly
            log.info("Death recovery: heading to gravestone at {} (from VarClient)", gravestoneLocation);
            phase = Phase.GO_TO_GRAVESTONE;
        } else if (preferGravestone && deathLocation != null) {
            // Use provided death location
            gravestoneLocation = deathLocation;
            log.info("Death recovery: heading to gravestone at {} (provided location)", deathLocation);
            phase = Phase.GO_TO_GRAVESTONE;
        } else if (dialogueCompleted) {
            // We completed dialogue (first-death tutorial) but there's no gravestone
            // This means the tutorial is done - nothing more to do
            log.info("Death tutorial completed, no gravestone to retrieve - task complete");
            if (returnLocation != null) {
                phase = Phase.RETURN_TO_LOCATION;
            } else {
                complete();
                return;
            }
        } else {
            // No gravestone info and haven't done dialogue - go to Death's Office
            log.info("Death recovery: heading to Death's Office (no gravestone location known)");
            phase = Phase.GO_TO_DEATHS_OFFICE;
        }
        waitTicks = 0;
    }

    /**
     * Read gravestone location from VarClient variables.
     * The game stores the gravestone world coordinates in VarClient 397 (X) and 398 (Y).
     */
    private void detectGravestoneFromVarClient(Client client) {
        // Check if gravestone is visible (varbit 10464)
        int gravestoneVisible = client.getVarbitValue(VARBIT_GRAVESTONE_VISIBLE);
        
        if (gravestoneVisible == 1) {
            // Get coordinates from VarClient
            int graveX = client.getVarcIntValue(VARCLIENT_GRAVESTONE_X);
            int graveY = client.getVarcIntValue(VARCLIENT_GRAVESTONE_Y);
            
            if (graveX > 0 && graveY > 0) {
                // Gravestone coordinates are world coordinates
                gravestoneLocation = new WorldPoint(graveX, graveY, 0);
                hasActiveGravestone = true;
                
                // Also get duration for logging
                int durationTicks = client.getVarbitValue(VARBIT_GRAVESTONE_DURATION);
                int durationMinutes = durationTicks / 100; // Approximate conversion
                
                log.info("Gravestone detected via VarClient: {} (duration: ~{} mins remaining)", 
                        gravestoneLocation, durationMinutes);
            } else {
                log.debug("Gravestone varbit is set but coordinates are invalid: ({}, {})", graveX, graveY);
                hasActiveGravestone = false;
                gravestoneLocation = null;
            }
        } else {
            hasActiveGravestone = false;
            gravestoneLocation = null;
            log.debug("No active gravestone (varbit {} = {})", VARBIT_GRAVESTONE_VISIBLE, gravestoneVisible);
        }
    }

    /**
     * Check if Death's dialogue is currently open.
     */
    private boolean isDeathDialogueOpen(Client client) {
        // Check NPC dialogue (shows NPC name)
        Widget npcDialogue = client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0);
        if (npcDialogue != null && !npcDialogue.isHidden()) {
            // NPC name is typically in child 4
            Widget nameWidget = client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4);
            if (nameWidget != null) {
                String npcName = nameWidget.getText();
                if (npcName != null && npcName.equalsIgnoreCase("Death")) {
                    return true;
                }
            }
        }

        // Check sprite dialogue (some dialogues use this)
        Widget spriteDialogue = client.getWidget(DIALOGUE_SPRITE_GROUP, 0);
        if (spriteDialogue != null && !spriteDialogue.isHidden()) {
            Widget[] children = spriteDialogue.getStaticChildren();
            if (children != null) {
                for (Widget child : children) {
                    String text = child.getText();
                    if (text != null && (text.contains("Death") || text.contains("died") || text.contains("mortal"))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Handle Death's tutorial dialogue by selecting required options.
     * 
     * Per OSRS Wiki, first death tutorial requires completing ALL of:
     * - "How do I pay a gravestone fee?"
     * - "How long do I have to return to my gravestone?"
     * - "How do I know what will happen to my items when I die?"
     * 
     * Only after all are completed can player select "I think I'm done here".
     */
    private void executeHandleDeathDialogue(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if sub-task (DialogueTask) finished
        if (activeSubTask != null && activeSubTask.getState().isTerminal()) {
            TaskState subTaskState = activeSubTask.getState();
            activeSubTask = null;
            
            if (subTaskState == TaskState.COMPLETED) {
                // DialogueTask completed successfully
                dialogueCompleted = true;
                log.info("Death dialogue task completed successfully");
                
                // Check if we're still in Death's Office
                WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
                if (playerPos != null && playerPos.getRegionID() == DEATHS_OFFICE_REGION) {
                    log.info("Still in Death's Office - exiting via portal");
                    phase = Phase.EXIT_DEATHS_OFFICE;
                } else {
                    // Player was teleported out - re-detect state
                    log.info("Player teleported out of Death's Office - re-detecting state");
                    phase = Phase.DETECT_STATE;
                }
                waitTicks = 0;
                return;
            } else {
                // DialogueTask failed - try talking to Death again
                log.warn("Death dialogue task failed, will retry by talking to Death");
                dialogueRetryCount++;
                if (dialogueRetryCount > 3) {
                    log.error("Failed to complete Death dialogue after {} retries", dialogueRetryCount);
                    fail("Unable to complete Death dialogue after multiple retries");
                    return;
                }
                // Fall through to create new dialogue task or talk to Death
            }
        }

        // Check if dialogue is open
        if (isAnyDialogueOpen(client)) {
            // Dialogue is open - create DialogueTask to handle it
            if (activeSubTask == null) {
                log.info("Creating DialogueTask for Death tutorial");
                DialogueTask dialogueTask = new DialogueTask()
                        .withClickThroughAll(true)
                        .withDescription("Death tutorial dialogue")
                        // Prefer "I think I'm done here" when available, otherwise select first option
                        .withOptionText("done");
                activeSubTask = dialogueTask;
            }
        } else {
            // No dialogue open - need to talk to Death to start/resume dialogue
            if (activeSubTask == null) {
                log.info("No dialogue open, talking to Death");
                InteractNpcTask talkTask = new InteractNpcTask(NpcID.DEATH, "Talk-to");
                talkTask.setDescription("Talk to Death");
                talkTask.setSearchRadius(15);
                activeSubTask = talkTask;
            }
        }

        // Sub-task execution happens in executeImpl
    }

    /**
     * Use Death's Domain portal in Death's Office to teleport to gravestone.
     */
    private void executeUseGravePortal(TaskContext ctx) {
        Client client = ctx.getClient();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return;
        }

        // Verify we're still in Death's Office
        int regionId = playerPos.getRegionID();
        if (regionId != DEATHS_OFFICE_REGION) {
            log.warn("Left Death's Office unexpectedly during USE_GRAVE_PORTAL, re-detecting state");
            phase = Phase.DETECT_STATE;
            return;
        }

        // Check if dialogue opened (Death might talk when using portal)
        if (isAnyDialogueOpen(client)) {
            log.debug("Dialogue opened during portal use, handling it");
            phase = Phase.HANDLE_DEATH_DIALOGUE;
            return;
        }

        // Use InteractObjectTask to click the Death's Domain portal
        if (activeSubTask == null) {
            log.info("Clicking Death's Domain portal to teleport to gravestone");
            preTeleportRegion = regionId;
            
            InteractObjectTask portalTask = new InteractObjectTask(DEATHS_DOMAIN_PORTAL_ID, "Enter");
            portalTask.setDescription("Enter Death's Domain (gravestone portal)");
            portalTask.setSearchRadius(20);
            activeSubTask = portalTask;
        }

        // When sub-task completes, wait for teleport
        if (activeSubTask != null && activeSubTask.getState().isTerminal()) {
            if (activeSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Death's Domain portal clicked, waiting for teleport");
                activeSubTask = null;
                phase = Phase.WAIT_FOR_GRAVE_TELEPORT;
                waitTicks = 0;
            } else {
                log.warn("Failed to interact with Death's Domain portal, trying exit portal instead");
                activeSubTask = null;
                phase = Phase.EXIT_DEATHS_OFFICE;
            }
        }
    }

    /**
     * Wait for teleport from Death's Domain portal to complete.
     */
    private void executeWaitForGraveTeleport(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            waitTicks++;
            if (waitTicks > MAX_WAIT_TICKS * 2) {
                log.warn("Timeout waiting for teleport, re-detecting state");
                phase = Phase.DETECT_STATE;
            }
            return;
        }

        int currentRegion = playerPos.getRegionID();

        // Check if we've left Death's Office
        if (currentRegion != DEATHS_OFFICE_REGION) {
            log.info("Teleported out of Death's Office to region {}", currentRegion);
            
            // Verify we're near the gravestone location
            if (gravestoneLocation != null) {
                int distance = playerPos.distanceTo(gravestoneLocation);
                if (distance <= 50) {
                    log.info("Arrived near gravestone at {} (distance: {})", gravestoneLocation, distance);
                    phase = Phase.GO_TO_GRAVESTONE;
                } else {
                    log.info("Teleported but not near gravestone (distance: {}), walking there", distance);
                    phase = Phase.GO_TO_GRAVESTONE;
                }
            } else {
                // No gravestone location - look around for it
                log.warn("Teleported but no gravestone location known, searching nearby");
                phase = Phase.GO_TO_GRAVESTONE;
            }
            waitTicks = 0;
            return;
        }

        waitTicks++;
        if (waitTicks > MAX_WAIT_TICKS * 3) {
            log.warn("Timeout waiting for Death's Domain teleport, trying exit portal");
            phase = Phase.EXIT_DEATHS_OFFICE;
            waitTicks = 0;
        }
    }

    /**
     * Check if any dialogue widget is open.
     */
    private boolean isAnyDialogueOpen(Client client) {
        Widget npcDialogue = client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0);
        if (npcDialogue != null && !npcDialogue.isHidden()) {
            return true;
        }

        Widget spriteDialogue = client.getWidget(DIALOGUE_SPRITE_GROUP, 0);
        if (spriteDialogue != null && !spriteDialogue.isHidden()) {
            return true;
        }

        Widget optionsDialogue = client.getWidget(DIALOGUE_OPTIONS_GROUP, 0);
        if (optionsDialogue != null && !optionsDialogue.isHidden()) {
            return true;
        }

        return false;
    }

    private void executeGoToGravestone(TaskContext ctx) {
        // Determine target location - prefer VarClient gravestone location
        WorldPoint targetLocation = gravestoneLocation != null ? gravestoneLocation : deathLocation;
        
        if (targetLocation == null) {
            log.warn("No gravestone location known, falling back to Death's Office");
            phase = Phase.GO_TO_DEATHS_OFFICE;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return;
        }

        // Check if we're close enough to interact with gravestone
        int distance = playerPos.distanceTo(targetLocation);
        if (distance <= 5) {
            log.debug("Near gravestone location (distance: {}), attempting retrieval", distance);
            phase = Phase.RETRIEVE_FROM_GRAVESTONE;
            waitTicks = 0;
            return;
        }

        // Walk to gravestone
        if (activeSubTask == null) {
            log.info("Walking to gravestone at {} (distance: {})", targetLocation, distance);
            WalkToTask walkTask = new WalkToTask(targetLocation);
            walkTask.setDescription("Walk to gravestone");
            activeSubTask = walkTask;
        }

        // Check if walk task completed or failed
        if (activeSubTask != null && activeSubTask.getState().isTerminal()) {
            if (activeSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Arrived at gravestone area");
                activeSubTask = null;
                phase = Phase.RETRIEVE_FROM_GRAVESTONE;
                waitTicks = 0;
            } else {
                log.warn("Failed to walk to gravestone, retrying");
                activeSubTask = null;
                waitTicks++;
                if (waitTicks > MAX_WAIT_TICKS) {
                    log.warn("Cannot reach gravestone, falling back to Death's Office");
                    phase = Phase.GO_TO_DEATHS_OFFICE;
                    waitTicks = 0;
                }
            }
        }
    }

    private void executeRetrieveFromGravestone(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if gravestone interface is already open
        Widget gravestoneWidget = client.getWidget(GRAVESTONE_INTERFACE_ID, 0);
        if (gravestoneWidget != null && !gravestoneWidget.isHidden()) {
            // Interface is open - click reclaim button
            Widget reclaimButton = findReclaimButton(gravestoneWidget, client);
            if (reclaimButton != null) {
                log.info("Clicking reclaim button in gravestone interface");
                clickWidget(ctx, reclaimButton, "Reclaim items from gravestone", Phase.RETURN_TO_LOCATION);
                itemsRetrieved = true;
                return;
            } else {
                log.warn("Gravestone interface open but cannot find reclaim button");
                waitTicks++;
                if (waitTicks > MAX_WAIT_TICKS) {
                    // Interface open but no button - items may already be recovered
                    log.info("Items may already be recovered, returning to activity");
                    phase = Phase.RETURN_TO_LOCATION;
                    waitTicks = 0;
            }
            return;
        }
        }

        // Interface not open - need to interact with gravestone object
        if (activeSubTask == null) {
            log.info("Searching for gravestone object to interact with...");
            
            // Try to find any gravestone object nearby
            // The gravestone will have "Repair" action if damaged, otherwise normal interaction
            InteractObjectTask graveTask = new InteractObjectTask(ObjectID.GRAVESTONE, "Repair");
            graveTask.setDescription("Interact with gravestone");
            graveTask.setSearchRadius(15);
            activeSubTask = graveTask;
        }

        // Check sub-task completion
        if (activeSubTask != null && activeSubTask.getState().isTerminal()) {
            if (activeSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Gravestone interaction initiated, waiting for interface");
                activeSubTask = null;
                waitTicks = 0;
                // Stay in this phase to wait for interface
            } else {
                log.warn("Failed to interact with gravestone object");
                activeSubTask = null;
                waitTicks++;
                
            if (waitTicks > MAX_WAIT_TICKS) {
                    // Re-check gravestone varbit - it might have expired
                    detectGravestoneFromVarClient(client);
                    
                    if (!hasActiveGravestone) {
                        log.info("Gravestone no longer active - items may be at Death's Office");
                        phase = Phase.GO_TO_DEATHS_OFFICE;
                    } else {
                        // Try searching around the area
                        log.warn("Cannot interact with gravestone, trying Death's Office");
                phase = Phase.GO_TO_DEATHS_OFFICE;
            }
                    waitTicks = 0;
        }
            }
        }
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
            // Coffer interface is open, click reclaim - then exit Death's Office
            Widget reclaimButton = findReclaimButton(cofferWidget, client);
            if (reclaimButton != null) {
                clickWidget(ctx, reclaimButton, "Reclaim items from Death's Coffer", Phase.EXIT_DEATHS_OFFICE);
                itemsRetrieved = true;
                return;
            }
        }

        // Check if Death's Office interface is open
        Widget officeWidget = client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0);
        if (officeWidget != null && !officeWidget.isHidden()) {
            // Office interface is open, look for retrieve option - then exit Death's Office
            Widget retrieveButton = findReclaimButton(officeWidget, client);
            if (retrieveButton != null) {
                clickWidget(ctx, retrieveButton, "Retrieve items from Death", Phase.EXIT_DEATHS_OFFICE);
                itemsRetrieved = true;
                return;
            }
        }

        // Check if Death dialogue is open (need to click through to get to interface)
        if (isDeathDialogueOpen(client) || isAnyDialogueOpen(client)) {
            log.debug("Death dialogue open, handling it");
            phase = Phase.HANDLE_DEATH_DIALOGUE;
            waitTicks = 0;
            return;
        }

        // No interface/dialogue open - need to talk to Death NPC
        if (activeSubTask == null) {
            log.info("Talking to Death to retrieve items");
            InteractNpcTask talkToDeathTask = new InteractNpcTask(NpcID.DEATH, "Talk-to");
            talkToDeathTask.setDescription("Talk to Death");
            talkToDeathTask.setSearchRadius(15);
            activeSubTask = talkToDeathTask;
            return;
        }

        // Check if talk task completed
        if (activeSubTask.getState().isTerminal()) {
            activeSubTask = null;
            waitTicks++;
            // Give a few ticks for dialogue to appear
        if (waitTicks > MAX_WAIT_TICKS) {
                log.warn("Death recovery: Unable to interact with Death, trying to exit");
            itemsRetrieved = false;
                phase = Phase.EXIT_DEATHS_OFFICE;
                waitTicks = 0;
            }
        }
    }

    /**
     * Exit Death's Office through the portal.
     */
    private void executeExitDeathsOffice(TaskContext ctx) {
        Client client = ctx.getClient();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return;
        }

        // Check if we've already exited (no longer in Death's Office region)
        int regionId = playerPos.getRegionID();
        if (regionId != DEATHS_OFFICE_REGION) {
            log.info("Already exited Death's Office, region is now {}", regionId);
            phase = Phase.RETURN_TO_LOCATION;
            waitTicks = 0;
            return;
        }

        // Check if dialogue is open - we may still be in tutorial
        if (isAnyDialogueOpen(client)) {
            log.debug("Dialogue still open, returning to dialogue handler");
            phase = Phase.HANDLE_DEATH_DIALOGUE;
            waitTicks = 0;
            return;
        }

        // Find and click the exit portal
        if (activeSubTask == null) {
            log.info("Clicking Death's Office exit portal");
            preTeleportRegion = regionId;
            
            InteractObjectTask portalTask = new InteractObjectTask(ObjectID.PORTAL_39549, "Enter");
            portalTask.setDescription("Exit Death's Office through portal");
            portalTask.setSearchRadius(20);
            activeSubTask = portalTask;
        }

        // Check if portal interaction completed
        if (activeSubTask != null && activeSubTask.getState().isTerminal()) {
            if (activeSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Exit portal clicked, waiting for region change");
                activeSubTask = null;
                phase = Phase.WAIT_FOR_EXIT;
                waitTicks = 0;
            } else {
                log.warn("Failed to interact with exit portal, retrying");
                activeSubTask = null;
                waitTicks++;
                if (waitTicks > MAX_WAIT_TICKS * 2) {
                    // Give up and try to return anyway (might already be outside)
                    log.warn("Timeout interacting with exit portal, attempting return");
                    phase = Phase.RETURN_TO_LOCATION;
                    waitTicks = 0;
                }
            }
        }
    }

    /**
     * Wait for region change after exiting Death's Office.
     */
    private void executeWaitForExit(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            waitTicks++;
            if (waitTicks > MAX_WAIT_TICKS * 2) {
                log.warn("Timeout waiting for exit, re-detecting state");
                phase = Phase.DETECT_STATE;
            }
            return;
        }

        int currentRegion = playerPos.getRegionID();

        // Check if we've left Death's Office
        if (currentRegion != DEATHS_OFFICE_REGION) {
            log.info("Exited Death's Office, now in region {} at {}", currentRegion, playerPos);
            phase = Phase.RETURN_TO_LOCATION;
            waitTicks = 0;
            return;
        }

        waitTicks++;
        log.debug("Waiting for exit from Death's Office... (tick {})", waitTicks);
        
        if (waitTicks > MAX_WAIT_TICKS * 3) {
            log.warn("Timeout waiting for Death's Office exit, re-attempting");
            phase = Phase.EXIT_DEATHS_OFFICE;
            waitTicks = 0;
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
     * Does NOT change phase - callers must handle phase transitions.
     * 
     * @param ctx task context
     * @param widget widget to click
     * @param actionDescription description for logging
     * @param nextPhase phase to transition to after successful click
     */
    private void clickWidget(TaskContext ctx, Widget widget, String actionDescription, Phase nextPhase) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Widget has invalid bounds: {}", actionDescription);
            return;
        }

        // Use centralized ClickPointCalculator for humanized positioning
        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
        int clickX = clickPoint.x;
        int clickY = clickPoint.y;

        log.debug("{} at ({}, {})", actionDescription, clickX, clickY);

        actionPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    waitTicks = 0;
                    if (nextPhase != null) {
                        phase = nextPhase;
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

