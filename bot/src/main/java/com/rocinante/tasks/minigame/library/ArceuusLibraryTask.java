package com.rocinante.tasks.minigame.library;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.InteractNpcTask;
import com.rocinante.tasks.impl.InteractObjectTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.tasks.minigame.MinigamePhase;
import com.rocinante.tasks.minigame.MinigameTask;
import com.rocinante.state.GameObjectSnapshot;
import com.rocinante.state.NpcSnapshot;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ObjectID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minigame task for training at the Arceuus Library.
 *
 * <p>The Arceuus Library allows players to find books for customers in exchange
 * for Books of Arcane Knowledge, which grant experience in Magic or Runecraft.
 *
 * <p>XP rewards scale with level:
 * <ul>
 *   <li>Magic: 15 × Magic level</li>
 *   <li>Runecraft: 5 × Runecraft level</li>
 * </ul>
 *
 * <p>This task integrates with RuneLite's Kourend Library plugin for book
 * location prediction, dramatically improving efficiency.
 *
 * @see ArceuusLibraryConfig
 * @see LibraryState
 */
@Slf4j
public class ArceuusLibraryTask extends MinigameTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Object ID for bookcases in the library (Arceuus Library).
     * Note: The library has multiple bookcase variations.
     */
    private static final int BOOKCASE_OBJECT_ID = 27669;

    /**
     * Book of Arcane Knowledge item ID.
     */
    private static final int BOOK_OF_ARCANE_KNOWLEDGE = 13513;

    /**
     * Pattern to extract book name from customer dialogue.
     */
    private static final Pattern BOOK_REQUEST_PATTERN = Pattern.compile(
            "'<col=0000ff>(.*)</col>'",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Dialogue patterns indicating book delivery success.
     */
    private static final List<String> DELIVERY_SUCCESS_PATTERNS = Arrays.asList(
            "You can have this other book",
            "please accept a token of my thanks",
            "Thanks, I'll get on with reading it"
    );

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final ArceuusLibraryConfig libraryConfig;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Current library activity.
     */
    @Getter
    private LibraryActivity currentActivity = LibraryActivity.IDLE;

    /**
     * Tracks book requests, locations, and statistics.
     */
    @Getter
    private final LibraryState libraryState;

    /**
     * Target bookcase to search.
     */
    private WorldPoint targetBookcase;

    /**
     * Active sub-task for delegation.
     */
    private Task activeSubTask;

    /**
     * Last activity timestamp for timeout detection.
     */
    private Instant lastActivityTime;

    /**
     * Activity timeout duration.
     */
    private static final Duration ACTIVITY_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Ticks spent idle.
     */
    private int idleTicks = 0;

    /**
     * Whether an inventory click is pending.
     */
    private boolean clickPending = false;

    /**
     * Plugin integration for book prediction.
     * Accessed via reflection since Library class is package-private.
     */
    @Nullable
    private Object libraryPluginLibrary;

    /**
     * Whether plugin integration has been attempted.
     */
    private boolean pluginAccessAttempted = false;

    /**
     * Whether plugin integration is permanently disabled due to errors.
     * Once disabled, we won't retry to avoid log spam.
     */
    private boolean pluginIntegrationDisabled = false;

    /**
     * Counter for reflection errors - if too many, disable integration.
     */
    private int reflectionErrorCount = 0;
    private static final int MAX_REFLECTION_ERRORS = 5;

    // ========================================================================
    // Activity Enum
    // ========================================================================

    public enum LibraryActivity {
        IDLE,
        TALKING_TO_CUSTOMER,
        SEARCHING_BOOKCASE,
        WALKING_TO_BOOKCASE,
        WALKING_TO_CUSTOMER,
        DELIVERING_BOOK,
        USING_REWARD
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create an Arceuus Library task with the specified configuration.
     *
     * @param config the library configuration
     */
    public ArceuusLibraryTask(ArceuusLibraryConfig config) {
        super(config);
        this.libraryConfig = config;
        this.libraryState = new LibraryState();
    }

    // ========================================================================
    // MinigameTask Implementation
    // ========================================================================

    @Override
    protected Skill getTrainedSkill() {
        return libraryConfig.getTargetSkill();
    }

    @Override
    protected void executeWaitingPhase(TaskContext ctx) {
        // Library doesn't have "rounds" - we're always ready to work
        // Try to initialize plugin access via reflection if not done (and not disabled)
        if (!pluginAccessAttempted && !pluginIntegrationDisabled) {
            tryInitializePluginAccess(ctx);
        }

        // Update plugin state (if integration is available)
        if (libraryPluginLibrary != null && !pluginIntegrationDisabled) {
            updateFromPlugin();
        }

        // Check if we already have a request
        if (libraryState.hasActiveRequest()) {
            // We already have a request, this means we're "in a round"
            // The phase transition will happen via isRoundActive()
            return;
        }

        idleTicks++;

        // Try to get a request from a customer
        if (idleTicks > 3) {
            startTalkingToCustomer(ctx);
            idleTicks = 0;
        }
    }

    /**
     * Try to access the RuneLite Kourend Library plugin's Library object via reflection.
     * 
     * <p><b>WARNING:</b> This uses reflection to access internal RuneLite plugin state.
     * This is fragile and may break if the plugin's internal structure changes.
     * The bot will continue to work without this integration, but with reduced efficiency.
     */
    private void tryInitializePluginAccess(TaskContext ctx) {
        pluginAccessAttempted = true;
        
        try {
            // Try to find KourendLibraryPlugin through client's plugin manager
            // This is a best-effort approach since the Library class is package-private
            Class<?> pluginClass;
            try {
                pluginClass = Class.forName("net.runelite.client.plugins.kourendlibrary.KourendLibraryPlugin");
            } catch (ClassNotFoundException e) {
                // Plugin not installed or class not found - this is expected if plugin isn't enabled
                log.info("KourendLibraryPlugin not found - library task will use fallback book location logic");
                return;
            }
            
            // Get PluginManager from client (if accessible)
            Method getPluginManager;
            try {
                getPluginManager = ctx.getClient().getClass().getMethod("getPluginManager");
            } catch (NoSuchMethodException e) {
                log.debug("Client doesn't expose getPluginManager - skipping plugin integration");
                return;
            }

            Object pluginManager = getPluginManager.invoke(ctx.getClient());
            if (pluginManager == null) {
                log.debug("PluginManager is null - skipping plugin integration");
                return;
            }

            Method getPlugins = pluginManager.getClass().getMethod("getPlugins");
            @SuppressWarnings("unchecked")
            java.util.Collection<?> plugins = (java.util.Collection<?>) getPlugins.invoke(pluginManager);
            
            boolean pluginFound = false;
            for (Object plugin : plugins) {
                if (pluginClass.isInstance(plugin)) {
                    pluginFound = true;
                    
                    // Validate expected field exists before accessing
                    Field libraryField;
                    try {
                        libraryField = pluginClass.getDeclaredField("library");
                    } catch (NoSuchFieldException e) {
                        log.warn("KourendLibraryPlugin structure changed - 'library' field not found. " +
                                "Plugin integration disabled. The bot will still work but with reduced efficiency.");
                        pluginIntegrationDisabled = true;
                        return;
                    }
                    
                    libraryField.setAccessible(true);
                    libraryPluginLibrary = libraryField.get(plugin);
                    
                    if (libraryPluginLibrary != null) {
                        // Validate the library object has expected methods
                        if (validateLibraryObjectStructure()) {
                            log.info("KourendLibraryPlugin integration active - book locations will be predicted");
                        } else {
                            log.warn("KourendLibraryPlugin Library object has unexpected structure. " +
                                    "Integration disabled. The bot will still work but with reduced efficiency.");
                            pluginIntegrationDisabled = true;
                            libraryPluginLibrary = null;
                        }
                    }
                    break;
                }
            }
            
            if (!pluginFound) {
                log.info("KourendLibraryPlugin is installed but not enabled - using fallback book location logic");
            }
            
        } catch (Exception e) {
            // Plugin not available or reflection failed - continue without it
            log.debug("Could not access KourendLibraryPlugin: {} - {}", 
                    e.getClass().getSimpleName(), e.getMessage());
            handleReflectionError("initialization", e);
        }
    }

    /**
     * Validate that the library object has the expected method signatures.
     * This catches structural changes that would cause runtime errors.
     */
    private boolean validateLibraryObjectStructure() {
        if (libraryPluginLibrary == null) {
            return false;
        }
        
        try {
            Class<?> libraryClass = libraryPluginLibrary.getClass();
            
            // Check for expected methods
            libraryClass.getDeclaredMethod("getBookcases");
            libraryClass.getDeclaredMethod("getCustomerBook");
            libraryClass.getDeclaredMethod("getCustomerId");
            
            return true;
        } catch (NoSuchMethodException e) {
            log.debug("Library object missing expected method: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Handle a reflection error with rate limiting.
     * If too many errors occur, disable plugin integration entirely.
     */
    private void handleReflectionError(String operation, Exception e) {
        reflectionErrorCount++;
        
        if (reflectionErrorCount >= MAX_REFLECTION_ERRORS) {
            log.warn("Too many reflection errors ({}) accessing KourendLibraryPlugin. " +
                    "Integration disabled. The bot will continue without plugin assistance.", 
                    reflectionErrorCount);
            pluginIntegrationDisabled = true;
            libraryPluginLibrary = null;
        } else {
            log.debug("Reflection error during {}: {} (error count: {})", 
                    operation, e.getMessage(), reflectionErrorCount);
        }
    }

    @Override
    protected void executeActivePhase(TaskContext ctx) {
        // Handle active sub-task first
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                activeSubTask = null;
                currentActivity = LibraryActivity.IDLE;
            }
            return;
        }

        // Skip if click pending
        if (clickPending) {
            return;
        }

        // Update state
        updateInventoryState(ctx);
        updateFromPlugin();
        checkDialogue(ctx);
        checkActivityTimeout();

        // Decide next action
        decideNextAction(ctx);
    }

    @Override
    protected void executeRewardsPhase(TaskContext ctx) {
        // Use any Books of Arcane Knowledge we've accumulated
        int rewardCount = countBooksOfArcaneKnowledge(ctx);

        if (rewardCount == 0) {
            // No rewards to use, back to waiting
            transitionToPhase(MinigamePhase.WAITING);
            return;
        }

        if (!clickPending) {
            useBookOfArcaneKnowledge(ctx);
        }
    }

    @Override
    protected boolean isRoundActive(TaskContext ctx) {
        // In library terms, we're "in a round" when we have an active book request
        return libraryState.hasActiveRequest() && isInMinigameArea(ctx);
    }

    @Override
    protected boolean shouldCollectRewards(TaskContext ctx) {
        // Check if we have reward books to use
        return countBooksOfArcaneKnowledge(ctx) >= 3;
    }

    @Override
    protected void onRoundStart(TaskContext ctx) {
        super.onRoundStart(ctx);
        currentActivity = LibraryActivity.IDLE;
        log.info("Book request received: {}", 
                libraryState.getRequestedBook() != null ? libraryState.getRequestedBook().getShortName() : "unknown");
    }

    @Override
    protected void onRoundEnd(TaskContext ctx) {
        super.onRoundEnd(ctx);
        libraryState.clearRequest();
        targetBookcase = null;
        currentActivity = LibraryActivity.IDLE;
        log.info("Book delivered! Total: {}", libraryState.getBooksDelivered());
    }

    @Override
    protected WorldPoint getEntryPoint(TaskContext ctx) {
        return ArceuusLibraryConfig.LIBRARY_CENTER;
    }

    // ========================================================================
    // Action Decision Logic
    // ========================================================================

    private void decideNextAction(TaskContext ctx) {
        if (currentActivity != LibraryActivity.IDLE) {
            return;
        }

        // Priority 1: Deliver book if we have the requested one
        if (libraryState.hasActiveRequest() && libraryState.hasRequestedBook()) {
            startDeliveringBook(ctx);
            return;
        }

        // Priority 2: Find the requested book
        if (libraryState.hasActiveRequest() && !libraryState.hasRequestedBook()) {
            startFindingBook(ctx);
            return;
        }

        // Priority 3: Get a new request if we don't have one
        if (!libraryState.hasActiveRequest()) {
            startTalkingToCustomer(ctx);
            return;
        }
    }

    // ========================================================================
    // Actions
    // ========================================================================

    private void startTalkingToCustomer(TaskContext ctx) {
        currentActivity = LibraryActivity.TALKING_TO_CUSTOMER;
        lastActivityTime = Instant.now();

        // Find nearest customer
        Optional<NpcSnapshot> customer = findNearestCustomer(ctx);
        if (customer.isEmpty()) {
            log.warn("No customers found");
            currentActivity = LibraryActivity.IDLE;
            return;
        }

        activeSubTask = new InteractNpcTask(customer.get().getId(), "Talk-to")
                .withDescription("Talk to library customer");
        log.trace("Talking to customer: {}", customer.get().getName());
    }

    private void startFindingBook(TaskContext ctx) {
        // Try to find the book location
        Optional<WorldPoint> bookLocation = findBookLocation();

        if (bookLocation.isPresent()) {
            targetBookcase = bookLocation.get();
            log.debug("Book {} located at {}", libraryState.getRequestedBook().getShortName(), targetBookcase);
        } else {
            // No known location, search systematically
            targetBookcase = getNextBookcaseToSearch(ctx);
        }

        if (targetBookcase == null) {
            log.warn("No bookcases available to search");
            currentActivity = LibraryActivity.IDLE;
            return;
        }

        // Check if we need to walk there
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        int dist = playerPos.distanceTo(targetBookcase);

        if (dist > 2) {
            currentActivity = LibraryActivity.WALKING_TO_BOOKCASE;
            activeSubTask = new WalkToTask(targetBookcase)
                    .withDescription("Walk to bookcase");
        } else {
            startSearchingBookcase(ctx);
        }
    }

    private void startSearchingBookcase(TaskContext ctx) {
        currentActivity = LibraryActivity.SEARCHING_BOOKCASE;
        lastActivityTime = Instant.now();

        activeSubTask = new InteractObjectTask(BOOKCASE_OBJECT_ID, "Search")
                .withDescription("Search bookcase");
        libraryState.recordSearch();
        log.trace("Searching bookcase at {}", targetBookcase);
    }

    private void startDeliveringBook(TaskContext ctx) {
        currentActivity = LibraryActivity.DELIVERING_BOOK;
        lastActivityTime = Instant.now();

        // Find the customer
        Optional<NpcSnapshot> customer = findRequestingCustomer(ctx);
        if (customer.isEmpty()) {
            log.warn("Cannot find customer for delivery");
            currentActivity = LibraryActivity.IDLE;
            return;
        }

        activeSubTask = new InteractNpcTask(customer.get().getId(), "Talk-to")
                .withDescription("Deliver book to customer");
        log.trace("Delivering {} to customer", libraryState.getRequestedBook().getShortName());
    }

    // ========================================================================
    // Customer Finding
    // ========================================================================

    private Optional<NpcSnapshot> findNearestCustomer(TaskContext ctx) {
        WorldState world = ctx.getWorldState();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();

        return world.getNearbyNpcs().stream()
                .filter(npc -> LibraryCustomer.isLibraryCustomer(npc.getId()))
                .min((a, b) -> {
                    int distA = playerPos.distanceTo(a.getWorldPosition());
                    int distB = playerPos.distanceTo(b.getWorldPosition());
                    return Integer.compare(distA, distB);
                });
    }

    private Optional<NpcSnapshot> findRequestingCustomer(TaskContext ctx) {
        WorldState world = ctx.getWorldState();

        int customerId = libraryState.getCustomerModelId();
        if (customerId > 0) {
            return world.getNearbyNpcs().stream()
                    .filter(npc -> npc.getId() == customerId)
                    .findFirst();
        }

        // Fall back to nearest customer
        return findNearestCustomer(ctx);
    }

    // ========================================================================
    // Book Finding
    // ========================================================================

    private Optional<WorldPoint> findBookLocation() {
        LibraryBook requested = libraryState.getRequestedBook();
        if (requested == null) return Optional.empty();

        // Check our local state first
        Optional<WorldPoint> localLoc = libraryState.getBookLocation(requested);
        if (localLoc.isPresent()) {
            return localLoc;
        }

        // Check plugin for predicted location (if integration is active)
        if (libraryPluginLibrary != null && !pluginIntegrationDisabled) {
            try {
                Method getBookcases = libraryPluginLibrary.getClass().getDeclaredMethod("getBookcases");
                getBookcases.setAccessible(true);
                List<?> bookcases = (List<?>) getBookcases.invoke(libraryPluginLibrary);

                for (Object bc : bookcases) {
                    Method getBook = bc.getClass().getDeclaredMethod("getBook");
                    getBook.setAccessible(true);
                    Object book = getBook.invoke(bc);

                    if (book != null) {
                        Method getItem = book.getClass().getDeclaredMethod("getItem");
                        getItem.setAccessible(true);
                        int itemId = (int) getItem.invoke(book);

                        if (itemId == requested.getItemId()) {
                            Method getLocation = bc.getClass().getDeclaredMethod("getLocation");
                            getLocation.setAccessible(true);
                            return Optional.of((WorldPoint) getLocation.invoke(bc));
                        }
                    }
                }
            } catch (Exception e) {
                handleReflectionError("findBookLocation", e);
            }
        }

        // Check our bookcase data
        LibraryBookcase bookcase = LibraryBookcase.findBook(requested);
        if (bookcase != null) {
            return Optional.of(bookcase.getLocation());
        }

        return Optional.empty();
    }

    @Nullable
    private WorldPoint getNextBookcaseToSearch(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerLoc = player.getWorldPosition();
        int currentFloor = playerLoc.getPlane();

        // Get unchecked bookcases on current floor first
        List<LibraryBookcase> unchecked = LibraryBookcase.getUnchecked();

        return unchecked.stream()
                .filter(bc -> bc.getLocation().getPlane() == currentFloor)
                .min((a, b) -> {
                    int distA = playerLoc.distanceTo(a.getLocation());
                    int distB = playerLoc.distanceTo(b.getLocation());
                    return Integer.compare(distA, distB);
                })
                .map(LibraryBookcase::getLocation)
                .orElseGet(() -> unchecked.isEmpty() ? null : unchecked.get(0).getLocation());
    }

    // ========================================================================
    // State Updates
    // ========================================================================

    private void updateFromPlugin() {
        if (libraryPluginLibrary == null || pluginIntegrationDisabled) {
            return;
        }

        try {
            Method getCustomerBook = libraryPluginLibrary.getClass().getDeclaredMethod("getCustomerBook");
            getCustomerBook.setAccessible(true);
            Object requestedBook = getCustomerBook.invoke(libraryPluginLibrary);

            if (requestedBook != null && !libraryState.hasActiveRequest()) {
                Method getItem = requestedBook.getClass().getDeclaredMethod("getItem");
                getItem.setAccessible(true);
                int itemId = (int) getItem.invoke(requestedBook);

                LibraryBook book = LibraryBook.byItemId(itemId);
                if (book != null) {
                    Method getCustomerId = libraryPluginLibrary.getClass().getDeclaredMethod("getCustomerId");
                    getCustomerId.setAccessible(true);
                    int customerId = (int) getCustomerId.invoke(libraryPluginLibrary);

                    libraryState.setRequest(book, null, customerId);
                    log.info("Got book request from plugin: {}", book.getShortName());
                }
            }
        } catch (Exception e) {
            handleReflectionError("updateFromPlugin", e);
        }
    }

    private void updateInventoryState(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();

        Set<Integer> bookItemIds = new HashSet<>();
        for (net.runelite.api.Item item : inventory.getNonEmptyItems()) {
            if (item != null && item.getId() > 0) {
                LibraryBook book = LibraryBook.byItemId(item.getId());
                if (book != null) {
                    bookItemIds.add(item.getId());
                }
            }
        }
        libraryState.updateInventoryBooks(bookItemIds);
        libraryState.setArcaneKnowledgeCount(inventory.countItem(BOOK_OF_ARCANE_KNOWLEDGE));
    }

    private void checkDialogue(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget dialogueWidget = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);

        if (dialogueWidget == null || dialogueWidget.getText() == null) {
            return;
        }

        String text = dialogueWidget.getText();

        // Check for book request
        Matcher matcher = BOOK_REQUEST_PATTERN.matcher(text);
        if (matcher.find() && !libraryState.hasActiveRequest()) {
            String bookName = matcher.group(1).replace("<br>", " ").trim();
            LibraryBook book = LibraryBook.byName(bookName);
            if (book != null) {
                libraryState.setRequest(book, null, -1);
                log.info("Book request from dialogue: {}", book.getShortName());
            }
        }

        // Check for delivery success
        for (String pattern : DELIVERY_SUCCESS_PATTERNS) {
            if (text.contains(pattern)) {
                handleDeliverySuccess(ctx);
                break;
            }
        }
    }

    private void handleDeliverySuccess(TaskContext ctx) {
        if (!libraryState.hasActiveRequest()) return;

        Client client = ctx.getClient();
        int level = client.getRealSkillLevel(libraryConfig.getTargetSkill());
        double xp = libraryConfig.getXpPerBook(level);

        libraryState.recordDelivery(xp);
        libraryState.removeBook(libraryState.getRequestedBook());

        // Trigger round end (delivery complete)
        onRoundEnd(ctx);

        // Check if we should use accumulated rewards
        if (shouldCollectRewards(ctx)) {
            transitionToPhase(MinigamePhase.REWARDS);
        } else {
            transitionToPhase(MinigamePhase.WAITING);
        }
    }

    private void checkActivityTimeout() {
        if (currentActivity == LibraryActivity.IDLE || lastActivityTime == null) {
            return;
        }

        Duration sinceActivity = Duration.between(lastActivityTime, Instant.now());
        if (sinceActivity.compareTo(ACTIVITY_TIMEOUT) >= 0) {
            log.debug("Activity {} timed out", currentActivity);
            currentActivity = LibraryActivity.IDLE;
        }
    }

    // ========================================================================
    // Reward Handling
    // ========================================================================

    private int countBooksOfArcaneKnowledge(TaskContext ctx) {
        return ctx.getInventoryState().countItem(BOOK_OF_ARCANE_KNOWLEDGE);
    }

    private void useBookOfArcaneKnowledge(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();
        InventoryClickHelper clickHelper = ctx.getInventoryClickHelper();

        if (clickHelper == null) {
            log.warn("InventoryClickHelper not available");
            return;
        }

        int slot = inventory.getSlotOf(BOOK_OF_ARCANE_KNOWLEDGE);
        if (slot < 0) {
            transitionToPhase(MinigamePhase.WAITING);
            return;
        }

        currentActivity = LibraryActivity.USING_REWARD;
        clickPending = true;

        String action = libraryConfig.getTargetSkill() == Skill.MAGIC ? "Magic" : "Runecraft";
        clickHelper.executeClick(slot, action)
                .thenAccept(success -> {
                    clickPending = false;
                    currentActivity = LibraryActivity.IDLE;
                    if (!success) {
                        log.warn("Failed to use Book of Arcane Knowledge");
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    currentActivity = LibraryActivity.IDLE;
                    log.error("Error using reward book", e);
                    return null;
                });
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("ArceuusLibrary[delivered=%d, activity=%s, skill=%s]",
                libraryState.getBooksDelivered(), currentActivity, libraryConfig.getTargetSkill().getName());
    }
}
