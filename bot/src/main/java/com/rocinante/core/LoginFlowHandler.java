package com.rocinante.core;

import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.quest.QuestExecutor;
import com.rocinante.quest.impl.TutorialIsland;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles in-game login flow screens and tutorial detection.
 * 
 * Flow (when on Tutorial Island):
 * 1. Check for "Set display name" widget → handle if present
 * 2. Check for character creation screen → handle if present  
 * 3. Handle tutorial steps (TODO)
 * 
 * NOTE: Pre-game screens (license agreement, Play button) are handled by
 * post_launch.py using OpenCV template matching.
 */
@Slf4j
@Singleton
public class LoginFlowHandler {

    // ========================================================================
    // Widget IDs for Tutorial Display Name screen (Interface 558)
    // ========================================================================
    private static final int TUTORIAL_DISPLAYNAME_GROUP = 558;
    private static final int NAME_TEXT_CHILD = 12;
    private static final int LOOK_UP_NAME_CHILD = 18;
    private static final int SET_NAME_CHILD = 19;

    // ========================================================================
    // Widget IDs for Character Creation / Player Design screen (Interface 679)
    // ========================================================================
    private static final int PLAYER_DESIGN_GROUP = 679;
    
    // Gender buttons
    private static final int GENDER_FEMALE_CHILD = 69;  // 0x45
    
    // Appearance style arrows (LEFT/RIGHT pairs)
    private static final int HEAD_LEFT = 15;
    private static final int HEAD_RIGHT = 16;
    private static final int JAW_LEFT = 19;
    private static final int JAW_RIGHT = 20;
    private static final int TORSO_LEFT = 23;
    private static final int TORSO_RIGHT = 24;
    private static final int ARMS_LEFT = 27;
    private static final int ARMS_RIGHT = 28;
    private static final int HANDS_LEFT = 31;
    private static final int HANDS_RIGHT = 32;
    private static final int LEGS_LEFT = 35;
    private static final int LEGS_RIGHT = 36;
    private static final int FEET_LEFT = 39;
    private static final int FEET_RIGHT = 40;
    
    // Color arrows (LEFT/RIGHT pairs)
    private static final int HAIR_COLOR_LEFT = 46;
    private static final int HAIR_COLOR_RIGHT = 47;
    private static final int TORSO_COLOR_LEFT = 50;
    private static final int TORSO_COLOR_RIGHT = 51;
    private static final int LEGS_COLOR_LEFT = 54;
    private static final int LEGS_COLOR_RIGHT = 55;
    private static final int FEET_COLOR_LEFT = 58;
    private static final int FEET_COLOR_RIGHT = 59;
    private static final int SKIN_LEFT = 62;
    private static final int SKIN_RIGHT = 63;
    
    // Confirm button
    private static final int CONFIRM_CHILD = 74;  // 0x4a

    // ========================================================================
    // Widget IDs for Experience Selection screen (Interface 929)
    // "How familiar are you with Old School RuneScape?"
    // ========================================================================
    private static final int EXPERIENCE_SELECT_GROUP = 929;
    private static final int BUTTON_EXPERIENCED_CHILD = 7;  // "I'm an experienced player"

    // ========================================================================
    // Tutorial Island regions
    // ========================================================================
    private static final Set<Integer> TUTORIAL_ISLAND_REGIONS = Set.of(
        12336, 12335, 12592, 12080, 12079, 12436
    );

    private final Client client;
    private final RobotMouseController mouseController;
    private final RobotKeyboardController keyboardController;
    private final HumanTimer humanTimer;
    private final Randomization randomization;
    private final Provider<QuestExecutor> questExecutorProvider;
    private final Provider<TaskExecutor> taskExecutorProvider;

    private final AtomicBoolean nameEntered = new AtomicBoolean(false);
    private final AtomicBoolean nameLookedUp = new AtomicBoolean(false);
    private final AtomicBoolean characterCreated = new AtomicBoolean(false);
    private final AtomicBoolean experienceSelected = new AtomicBoolean(false);
    private final AtomicBoolean actionInProgress = new AtomicBoolean(false);
    private final AtomicBoolean onTutorialIsland = new AtomicBoolean(false);
    private final AtomicBoolean tutorialQuestStarted = new AtomicBoolean(false);
    private final AtomicBoolean loginFlowComplete = new AtomicBoolean(false);
    
    private volatile long lastCheckTick = 0;
    private static final int CHECK_INTERVAL_TICKS = 3;
    
    // Logging throttle
    private volatile long lastStatusLogTime = 0;
    private static final long STATUS_LOG_INTERVAL_MS = 5000;

    private final String characterName;

    @Inject
    public LoginFlowHandler(
            Client client,
            RobotMouseController mouseController,
            RobotKeyboardController keyboardController,
            HumanTimer humanTimer,
            Randomization randomization,
            Provider<QuestExecutor> questExecutorProvider,
            Provider<TaskExecutor> taskExecutorProvider) {
        this.client = client;
        this.mouseController = mouseController;
        this.keyboardController = keyboardController;
        this.humanTimer = humanTimer;
        this.randomization = randomization;
        this.questExecutorProvider = questExecutorProvider;
        this.taskExecutorProvider = taskExecutorProvider;
        
        this.characterName = System.getenv("CHARACTER_NAME");
        
        log.info("LoginFlowHandler initialized. CHARACTER_NAME={}", 
                characterName != null ? characterName : "<not set>");
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        
        // Login flow is complete - nothing more to do
        if (loginFlowComplete.get()) {
            return;
        }
        
        // Rate limit checks
        long currentTick = client.getTickCount();
        if (currentTick - lastCheckTick < CHECK_INTERVAL_TICKS) {
            return;
        }
        lastCheckTick = currentTick;
        
        if (actionInProgress.get()) {
            return;
        }
        
        // STEP 1: Check if we're on Tutorial Island (this gates everything)
        boolean wasOnTutorial = onTutorialIsland.get();
        boolean isOnTutorial = checkTutorialIsland();
        
        if (!isOnTutorial) {
            // Not on tutorial island - mark login flow as complete
            log.info("[LOGIN] Not on Tutorial Island - login flow complete (existing account)");
            loginFlowComplete.set(true);
            return;
        }
        
        // Log status periodically when on tutorial island (only while handling setup)
        logTutorialStatus();
        
        // Read the tutorial progress varp (VarPlayer, not varbit) - this is the source of truth
        // varp 281: 1 = customization, 2+ = past customization
        int tutorialVarbit = client.getVarpValue(TutorialIsland.VARP_TUTORIAL_PROGRESS);
        
        // Check what screens are visible
        boolean displayNameVisible = isDisplayNameScreenVisible();
        boolean charCreationVisible = isCharacterCreationVisible();
        boolean experienceVisible = isExperienceSelectVisible();
        
        // STEP 2: Handle display name screen if visible
        if (!nameEntered.get()) {
            if (displayNameVisible) {
                log.info("[TUTORIAL] Display name screen detected - handling...");
                handleDisplayNameScreen();
                return;
            }
            // If varbit >= 2, the game says we're past customization - trust the varbit
            if (tutorialVarbit >= 2) {
                log.info("[TUTORIAL] Varbit {} >= 2 - name/customization already complete", tutorialVarbit);
                nameEntered.set(true);
                characterCreated.set(true);
                experienceSelected.set(true);
            }
        }
        
        // STEP 3: Handle character creation screen if visible
        if (!characterCreated.get()) {
            if (charCreationVisible) {
                log.info("[TUTORIAL] Character creation screen detected - handling...");
                handleCharacterCreation();
                return;
            }
        }
        
        // STEP 4: Handle experience selection screen if visible
        if (!experienceSelected.get()) {
            if (experienceVisible) {
                log.info("[TUTORIAL] Experience selection screen detected - selecting 'Experienced'...");
                handleExperienceSelection();
                return;
            }
        }
        
        // STEP 5: All initial setup done - start Tutorial Island quest
        if (nameEntered.get() && characterCreated.get() && experienceSelected.get()) {
            if (!tutorialQuestStarted.get()) {
                startTutorialIslandQuest();
            }
        }
    }

    /**
     * Start the Tutorial Island quest via QuestExecutor.
     * This hands off tutorial completion to the quest system.
     */
    private void startTutorialIslandQuest() {
        if (tutorialQuestStarted.getAndSet(true)) {
            return; // Already started
        }
        
        log.info("[TUTORIAL] ✓ Login flow complete - starting Tutorial Island quest");
        
        try {
            // Start the TaskExecutor so queued tasks will execute
            TaskExecutor taskExecutor = taskExecutorProvider.get();
            taskExecutor.start();
            log.info("[TUTORIAL] TaskExecutor started");
            
            // Start the Tutorial Island quest
            QuestExecutor questExecutor = questExecutorProvider.get();
            TutorialIsland tutorialIsland = new TutorialIsland();
            questExecutor.startQuest(tutorialIsland);
            
            log.info("[TUTORIAL] Tutorial Island quest activated - QuestExecutor will handle remaining steps");
            loginFlowComplete.set(true);
        } catch (Exception e) {
            log.error("[TUTORIAL] Failed to start Tutorial Island quest", e);
            tutorialQuestStarted.set(false); // Allow retry
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN) {
            reset();
            log.info("[LOGIN] Reset on logout");
        } else if (event.getGameState() == GameState.LOGGED_IN) {
            log.info("[LOGIN] Logged in - will check for tutorial island");
        }
    }

    /**
     * Log current tutorial status periodically.
     */
    private void logTutorialStatus() {
        long now = System.currentTimeMillis();
        if (now - lastStatusLogTime < STATUS_LOG_INTERVAL_MS) {
            return;
        }
        lastStatusLogTime = now;
        
        int tutorialVarp = client.getVarpValue(TutorialIsland.VARP_TUTORIAL_PROGRESS);
        
        log.info("[TUTORIAL STATUS] varp281={}, onTutorialIsland={}, nameEntered={}, characterCreated={}, experienceSelected={}, questStarted={}",
                tutorialVarp, onTutorialIsland.get(), nameEntered.get(), characterCreated.get(), experienceSelected.get(), tutorialQuestStarted.get());
        
        // Also log what widgets we can see
        boolean displayNameVisible = isDisplayNameScreenVisible();
        boolean charCreationVisible = isCharacterCreationVisible();
        boolean experienceVisible = isExperienceSelectVisible();
        log.info("[TUTORIAL STATUS] DisplayNameWidget={}, CharCreationWidget={}, ExperienceWidget={}", 
                displayNameVisible, charCreationVisible, experienceVisible);
    }

    /**
     * Check if player is on Tutorial Island.
     * @return true if on tutorial island
     */
    private boolean checkTutorialIsland() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return false;
        }
        
        WorldPoint location = localPlayer.getWorldLocation();
        if (location == null) {
            return false;
        }
        
        int regionId = location.getRegionID();
        boolean isOnTutorial = TUTORIAL_ISLAND_REGIONS.contains(regionId);
        
        // Update state and log changes
        boolean wasOnTutorial = onTutorialIsland.getAndSet(isOnTutorial);
        if (isOnTutorial && !wasOnTutorial) {
            log.info("[TUTORIAL] === TUTORIAL ISLAND DETECTED === Region: {}", regionId);
        } else if (!isOnTutorial && wasOnTutorial) {
            log.info("[TUTORIAL] Left Tutorial Island - Region: {}", regionId);
        }
        
        return isOnTutorial;
    }

    // ========================================================================
    // Display Name Screen
    // ========================================================================

    private boolean isDisplayNameScreenVisible() {
        Widget displayNameWidget = client.getWidget(TUTORIAL_DISPLAYNAME_GROUP, 0);
        boolean visible = displayNameWidget != null && !displayNameWidget.isHidden();
        if (visible) {
            log.debug("[TUTORIAL] Display name widget group {} is visible", TUTORIAL_DISPLAYNAME_GROUP);
        }
        return visible;
    }

    private void handleDisplayNameScreen() {
        if (characterName == null || characterName.isEmpty()) {
            log.warn("[TUTORIAL] Cannot enter name: CHARACTER_NAME not set - marking as done");
            nameEntered.set(true);
            return;
        }
        
        Widget setNameButton = client.getWidget(TUTORIAL_DISPLAYNAME_GROUP, SET_NAME_CHILD);
        Widget lookupButton = client.getWidget(TUTORIAL_DISPLAYNAME_GROUP, LOOK_UP_NAME_CHILD);
        
        // Debug: log what we found
        log.debug("[TUTORIAL] lookupButton={}, setNameButton={}", 
                lookupButton != null && !lookupButton.isHidden(),
                setNameButton != null && !setNameButton.isHidden());
        
        if (!nameLookedUp.get() && lookupButton != null && !lookupButton.isHidden()) {
            handleNameLookup();
        } else if (setNameButton != null && !setNameButton.isHidden()) {
            handleSetName();
        }
    }

    private void handleNameLookup() {
        Widget nameInput = client.getWidget(TUTORIAL_DISPLAYNAME_GROUP, NAME_TEXT_CHILD);
        Widget lookupButton = client.getWidget(TUTORIAL_DISPLAYNAME_GROUP, LOOK_UP_NAME_CHILD);
        
        if (nameInput == null || lookupButton == null) {
            log.warn("[TUTORIAL] Name input or lookup button not found");
            return;
        }
        
        Rectangle inputBounds = nameInput.getBounds();
        Rectangle lookupBounds = lookupButton.getBounds();
        
        if (inputBounds == null || lookupBounds == null || 
            inputBounds.width <= 0 || lookupBounds.width <= 0) {
            log.warn("[TUTORIAL] Invalid widget bounds - input={}, lookup={}", inputBounds, lookupBounds);
            return;
        }
        
        log.info("[TUTORIAL] Entering name '{}' and clicking Lookup...", characterName);
        actionInProgress.set(true);
        
        humanTimer.sleep(DelayProfile.REACTION)
            .thenCompose(v -> mouseController.click(inputBounds))
            .thenCompose(v -> humanTimer.sleep(DelayProfile.ACTION_GAP))
            .thenCompose(v -> keyboardController.type(characterName))
            .thenCompose(v -> humanTimer.sleep(DelayProfile.REACTION))
            .thenCompose(v -> mouseController.click(lookupBounds))
            .thenRun(() -> {
                log.info("[TUTORIAL] Name lookup initiated for: {}", characterName);
                nameLookedUp.set(true);
                actionInProgress.set(false);
            })
            .exceptionally(e -> {
                log.error("[TUTORIAL] Failed to lookup name", e);
                actionInProgress.set(false);
                return null;
            });
    }

    private void handleSetName() {
        Widget setNameButton = client.getWidget(TUTORIAL_DISPLAYNAME_GROUP, SET_NAME_CHILD);
        
        if (setNameButton == null || setNameButton.isHidden()) {
            log.debug("[TUTORIAL] Set name button not visible yet");
            return;
        }
        
        Rectangle setNameBounds = setNameButton.getBounds();
        if (setNameBounds == null || setNameBounds.width <= 0) {
            log.warn("[TUTORIAL] Invalid bounds for Set name button: {}", setNameBounds);
            return;
        }
        
        log.info("[TUTORIAL] Clicking 'Set name' to confirm: {}", characterName);
        actionInProgress.set(true);
        
        humanTimer.sleep(DelayProfile.REACTION)
            .thenCompose(v -> mouseController.click(setNameBounds))
            .thenRun(() -> {
                log.info("[TUTORIAL] ✓ Character name set: {}", characterName);
                nameEntered.set(true);
                actionInProgress.set(false);
            })
            .exceptionally(e -> {
                log.error("[TUTORIAL] Failed to set name", e);
                actionInProgress.set(false);
                return null;
            });
    }

    // ========================================================================
    // Character Creation Screen
    // ========================================================================

    private boolean isCharacterCreationVisible() {
        Widget playerDesignWidget = client.getWidget(PLAYER_DESIGN_GROUP, 0);
        boolean visible = playerDesignWidget != null && !playerDesignWidget.isHidden();
        if (visible) {
            log.debug("[TUTORIAL] Character creation widget group {} is visible", PLAYER_DESIGN_GROUP);
        }
        return visible;
    }

    /**
     * Handle character creation by:
     * 1. Always selecting female (for Recruitment Drive quest)
     * 2. Clicking head/hair, torso, legs, and color arrows randomly
     * 3. Confirming
     * 
     * NOTE: All widget bounds are pre-computed on the client thread before
     * starting the async click chain.
     */
    private void handleCharacterCreation() {
        log.info("[TUTORIAL] Starting character creation - randomizing appearance...");
        
        // PRE-COMPUTE all widget bounds on the client thread (we're on it now)
        List<Rectangle> clickTargets = new ArrayList<>();
        List<String> clickNames = new ArrayList<>();
        
        // Step 1: Female gender button
        addWidgetBounds(clickTargets, clickNames, GENDER_FEMALE_CHILD, "Female");
        
        // Step 2: Randomize main features
        addRandomWidgetBounds(clickTargets, clickNames, HEAD_LEFT, HEAD_RIGHT, "Head", 1, 5);
        addRandomWidgetBounds(clickTargets, clickNames, TORSO_LEFT, TORSO_RIGHT, "Torso", 1, 5);
        addRandomWidgetBounds(clickTargets, clickNames, LEGS_LEFT, LEGS_RIGHT, "Legs", 1, 5);
        addRandomWidgetBounds(clickTargets, clickNames, HAIR_COLOR_LEFT, HAIR_COLOR_RIGHT, "HairColor", 1, 7);
        addRandomWidgetBounds(clickTargets, clickNames, TORSO_COLOR_LEFT, TORSO_COLOR_RIGHT, "TorsoColor", 1, 5);
        addRandomWidgetBounds(clickTargets, clickNames, LEGS_COLOR_LEFT, LEGS_COLOR_RIGHT, "LegsColor", 1, 5);
        addRandomWidgetBounds(clickTargets, clickNames, SKIN_LEFT, SKIN_RIGHT, "Skin", 0, 3);
        
        // Step 3: Random other options
        addRandomWidgetBounds(clickTargets, clickNames, JAW_LEFT, JAW_RIGHT, "Jaw", 0, 2);
        addRandomWidgetBounds(clickTargets, clickNames, ARMS_LEFT, ARMS_RIGHT, "Arms", 0, 2);
        addRandomWidgetBounds(clickTargets, clickNames, HANDS_LEFT, HANDS_RIGHT, "Hands", 0, 2);
        addRandomWidgetBounds(clickTargets, clickNames, FEET_LEFT, FEET_RIGHT, "Feet", 0, 2);
        addRandomWidgetBounds(clickTargets, clickNames, FEET_COLOR_LEFT, FEET_COLOR_RIGHT, "FeetColor", 0, 2);
        
        // Get confirm button bounds
        Rectangle confirmBounds = getWidgetBounds(CONFIRM_CHILD);
        
        if (clickTargets.isEmpty()) {
            log.warn("[TUTORIAL] No valid click targets found for character creation");
            characterCreated.set(true);
            return;
        }
        
        log.info("[TUTORIAL] Will execute {} appearance changes", clickTargets.size());
        actionInProgress.set(true);
        
        // Execute all clicks using pre-computed bounds (thread-safe)
        executeClicks(clickTargets, clickNames, 0)
            .thenCompose(v -> humanTimer.sleep(DelayProfile.ACTION_GAP))
            .thenCompose(v -> {
                log.info("[TUTORIAL] Clicking Confirm...");
                if (confirmBounds != null) {
                    return mouseController.click(confirmBounds);
                }
                log.warn("[TUTORIAL] Confirm button bounds not available");
                return CompletableFuture.completedFuture(null);
            })
            .thenRun(() -> {
                log.info("[TUTORIAL] ✓ Character creation completed!");
                characterCreated.set(true);
                actionInProgress.set(false);
            })
            .exceptionally(e -> {
                log.error("[TUTORIAL] Character creation failed", e);
                actionInProgress.set(false);
                return null;
            });
    }

    /**
     * Get bounds for a single widget (on client thread).
     */
    private Rectangle getWidgetBounds(int childId) {
        Widget widget = client.getWidget(PLAYER_DESIGN_GROUP, childId);
        if (widget == null || widget.isHidden()) {
            return null;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width <= 0) {
            return null;
        }
        return bounds;
    }

    /**
     * Add a single widget's bounds to the click list (on client thread).
     */
    private void addWidgetBounds(List<Rectangle> targets, List<String> names, int childId, String name) {
        Rectangle bounds = getWidgetBounds(childId);
        if (bounds != null) {
            targets.add(bounds);
            names.add(name);
            log.debug("[TUTORIAL] Added {} bounds: {}", name, bounds);
        } else {
            log.warn("[TUTORIAL] Widget not found: {}", name);
        }
    }

    /**
     * Add random clicks to a left/right arrow pair (on client thread).
     * Pre-computes the bounds for all clicks.
     */
    private void addRandomWidgetBounds(List<Rectangle> targets, List<String> names,
                                        int leftChild, int rightChild, 
                                        String name, int minClicks, int maxClicks) {
        int clicks = randomization.uniformRandomInt(minClicks, maxClicks);
        if (clicks <= 0) {
            return;
        }
        
        boolean goRight = randomization.chance(0.5);
        int childId = goRight ? rightChild : leftChild;
        String dir = goRight ? "→" : "←";
        
        Rectangle bounds = getWidgetBounds(childId);
        if (bounds == null) {
            log.warn("[TUTORIAL] Widget not found for {}", name);
            return;
        }
        
        log.debug("[TUTORIAL] {} will click {} {} times", name, dir, clicks);
        
        for (int i = 0; i < clicks; i++) {
            targets.add(bounds);
            names.add(name + " " + dir);
        }
    }

    /**
     * Execute pre-computed clicks sequentially (thread-safe, uses pre-computed bounds).
     */
    private CompletableFuture<Void> executeClicks(List<Rectangle> targets, List<String> names, int index) {
        if (index >= targets.size()) {
            return CompletableFuture.completedFuture(null);
        }
        
        Rectangle bounds = targets.get(index);
        String name = names.get(index);
        
        return humanTimer.sleep(DelayProfile.REACTION)
            .thenCompose(v -> {
                log.trace("[TUTORIAL] Clicking: {} ({}/{})", name, index + 1, targets.size());
                return mouseController.click(bounds);
            })
            .thenCompose(v -> humanTimer.sleep(DelayProfile.ACTION_GAP))
            .thenCompose(v -> executeClicks(targets, names, index + 1));
    }

    // ========================================================================
    // Experience Selection Screen
    // ========================================================================

    private boolean isExperienceSelectVisible() {
        Widget experienceWidget = client.getWidget(EXPERIENCE_SELECT_GROUP, 0);
        boolean visible = experienceWidget != null && !experienceWidget.isHidden();
        if (visible) {
            log.debug("[TUTORIAL] Experience selection widget group {} is visible", EXPERIENCE_SELECT_GROUP);
        }
        return visible;
    }

    /**
     * Handle the "How familiar are you with OSRS?" dialog.
     * Always clicks "I'm an experienced player" (option 3).
     */
    private void handleExperienceSelection() {
        Widget experiencedButton = client.getWidget(EXPERIENCE_SELECT_GROUP, BUTTON_EXPERIENCED_CHILD);
        
        if (experiencedButton == null || experiencedButton.isHidden()) {
            log.warn("[TUTORIAL] Experienced button not found");
            return;
        }
        
        Rectangle bounds = experiencedButton.getBounds();
        if (bounds == null || bounds.width <= 0) {
            log.warn("[TUTORIAL] Invalid bounds for Experienced button");
            return;
        }
        
        log.info("[TUTORIAL] Clicking 'I'm an experienced player'...");
        actionInProgress.set(true);
        
        humanTimer.sleep(DelayProfile.REACTION)
            .thenCompose(v -> mouseController.click(bounds))
            .thenRun(() -> {
                log.info("[TUTORIAL] ✓ Experience level selected: Experienced");
                experienceSelected.set(true);
                actionInProgress.set(false);
            })
            .exceptionally(e -> {
                log.error("[TUTORIAL] Failed to select experience level", e);
                actionInProgress.set(false);
                return null;
            });
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public boolean isOnTutorialIsland() {
        return onTutorialIsland.get();
    }

    public boolean isNameEntryComplete() {
        return nameEntered.get();
    }

    public boolean isCharacterCreationComplete() {
        return characterCreated.get();
    }

    public boolean isExperienceSelectionComplete() {
        return experienceSelected.get();
    }

    public boolean isLoginFlowComplete() {
        return loginFlowComplete.get();
    }

    public boolean isTutorialQuestStarted() {
        return tutorialQuestStarted.get();
    }

    public void reset() {
        nameEntered.set(false);
        nameLookedUp.set(false);
        characterCreated.set(false);
        experienceSelected.set(false);
        actionInProgress.set(false);
        onTutorialIsland.set(false);
        tutorialQuestStarted.set(false);
        loginFlowComplete.set(false);
        log.debug("[LOGIN] State reset");
    }
}
