package com.rocinante.status;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.core.GameStateService;
import com.rocinante.quest.QuestService;
import com.rocinante.quest.bridge.RequirementStatus;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.util.IoExecutor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.callback.ClientThread;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes bot status to a JSON file for external consumption.
 * 
 * Writes status updates to a shared file that can be read by the web server.
 * Updates are written at most once per second to avoid excessive I/O.
 * 
 * The status file location defaults to:
 * ~/.runelite/rocinante/status.json
 */
@Slf4j
@Singleton
public class StatusPublisher {

    /**
     * Status file location (hardcoded to bolt-launcher RuneLite path).
     */
    private static final String DEFAULT_STATUS_DIR = "/home/runelite/.local/share/bolt-launcher/.runelite/rocinante";
    private static final String STATUS_FILE_NAME = "status.json";

    /**
     * Minimum interval between status writes (milliseconds).
     */
    private static final long MIN_WRITE_INTERVAL_MS = 1000;

    /**
     * Minimum interval between quest data refreshes (milliseconds).
     * Keeps heavy quest scans off back-to-back ticks.
     */
    private static final long MIN_QUEST_REFRESH_INTERVAL_MS = 1500;

    /**
     * Game ticks between status updates (roughly 1 second = ~1.67 ticks).
     */
    private static final int TICKS_BETWEEN_UPDATES = 2;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final Provider<GameStateService> gameStateServiceProvider;
    private final IoExecutor ioExecutor;
    private final ClientThread clientThread;
    
    @Setter
    @Nullable
    private TaskExecutor taskExecutor;
    
    @Setter
    @Nullable
    private XpTracker xpTracker;
    
    @Setter
    @Nullable
    private FatigueModel fatigueModel;
    
    @Setter
    @Nullable
    private BreakScheduler breakScheduler;

    @Setter
    @Nullable
    private QuestService questService;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Cached quest data (expensive to compute, refreshed on demand).
     */
    @Nullable
    private volatile BotStatus.QuestsData cachedQuestsData = BotStatus.QuestsData.empty();

    /**
     * Flag to trigger quest data refresh on next tick.
     */
    private volatile boolean questRefreshPending = true;

    /**
     * Whether a quest refresh is currently running (client thread).
     */
    private final AtomicBoolean questRefreshInFlight = new AtomicBoolean(false);

    /**
     * Last quest refresh wall-clock time.
     */
    private volatile long lastQuestRefreshMs = 0;

    /**
     * Track skill levels to detect level-ups.
     */
    private final Map<Skill, Integer> lastKnownLevels = new HashMap<>();

    /**
     * Path to the status file.
     */
    private Path statusFilePath;

    /**
     * Last time status was written.
     */
    private volatile long lastWriteTime = 0;

    /**
     * Tick counter for periodic updates.
     */
    private int tickCounter = 0;

    /**
     * Whether publishing is enabled.
     */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Last status snapshot (for comparison to detect changes).
     */
    private volatile BotStatus lastStatus;

    @Inject
    public StatusPublisher(Client client,
                           Provider<GameStateService> gameStateServiceProvider,
                           IoExecutor ioExecutor,
                           ClientThread clientThread) {
        this.client = client;
        this.gameStateServiceProvider = gameStateServiceProvider;
        this.ioExecutor = ioExecutor;
        this.clientThread = clientThread;
        
        // Initialize status directory
        initializeStatusDirectory();
        
        log.info("StatusPublisher initialized, output: {}", statusFilePath);
    }

    /**
     * Initialize the status directory and file path.
     */
    private void initializeStatusDirectory() {
        Path dirPath = Paths.get(DEFAULT_STATUS_DIR);
        try {
            Files.createDirectories(dirPath);
            statusFilePath = dirPath.resolve(STATUS_FILE_NAME);
            log.debug("Status directory initialized: {}", dirPath);
        } catch (IOException e) {
            log.error("Failed to create status directory: {}", dirPath, e);
            // Fall back to temp directory
            statusFilePath = Paths.get(System.getProperty("java.io.tmpdir"), STATUS_FILE_NAME);
            log.warn("Using fallback status path: {}", statusFilePath);
        }
    }

    /**
     * Start publishing status updates.
     */
    public void start() {
        enabled.set(true);
        log.info("Status publishing started");
        
        // Write initial status immediately
        writeStatus();
    }

    /**
     * Stop publishing status updates.
     */
    public void stop() {
        enabled.set(false);
        log.info("Status publishing stopped");
        
        // Write final status
        writeStatus();
    }

    /**
     * Check if publishing is enabled.
     * 
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Handle game tick events to trigger periodic status updates.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!enabled.get()) {
            return;
        }

        // Refresh quest data if pending (initial load, level up, or manual refresh)
        scheduleQuestRefreshIfNeeded();

        tickCounter++;
        if (tickCounter >= TICKS_BETWEEN_UPDATES) {
            tickCounter = 0;
            writeStatusIfNeeded();
        }
    }

    /**
     * Handle stat changes to detect level-ups.
     * When a skill levels up, quest requirements may now be met.
     */
    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (!enabled.get()) {
            return;
        }

        Skill skill = event.getSkill();
        int newLevel = event.getLevel();
        Integer lastLevel = lastKnownLevels.get(skill);

        if (lastLevel != null && newLevel > lastLevel) {
            log.info("Level up detected: {} {} -> {}, refreshing quest data", skill.getName(), lastLevel, newLevel);
            questRefreshPending = true;
        }

        lastKnownLevels.put(skill, newLevel);
    }

    /**
     * Force a refresh of quest data.
     * Called from CommandProcessor when UI requests refresh.
     */
    public void requestQuestRefresh() {
        log.info("Quest data refresh requested");
        questRefreshPending = true;
    }

    /**
     * Schedule a quest refresh off the tick path with rate limiting and in-flight guard.
     */
    private void scheduleQuestRefreshIfNeeded() {
        if (!questRefreshPending) {
            return;
        }

        // Don't spam refreshes
        long now = System.currentTimeMillis();
        if (now - lastQuestRefreshMs < MIN_QUEST_REFRESH_INTERVAL_MS) {
            return;
        }

        // Only one refresh at a time
        if (!questRefreshInFlight.compareAndSet(false, true)) {
            return;
        }

        // Capture lightweight quest state on the client thread, then offload heavy
        // requirement assembly to the IO executor to avoid blocking ticks.
        clientThread.invokeLater(() -> {
            QuestStateSnapshot snapshot = captureQuestStateSnapshot();
            ioExecutor.submit(() -> {
                try {
                    refreshQuestData(snapshot);
                    lastQuestRefreshMs = System.currentTimeMillis();
                    questRefreshPending = false;
                } catch (Exception e) {
                    log.error("Failed to refresh quest data", e);
                    cachedQuestsData = BotStatus.QuestsData.empty();
                } finally {
                    questRefreshInFlight.set(false);
                }
            });
        });
    }

    /**
     * Refresh the cached quest data from QuestService using the provided snapshot.
     */
    private void refreshQuestData(QuestStateSnapshot snapshot) {
        if (questService == null) {
            log.debug("QuestService not available, skipping quest data refresh");
            cachedQuestsData = BotStatus.QuestsData.empty();
            return;
        }

        log.debug("Refreshing quest data from QuestService (off-thread), snapshot has {} entries...", 
                snapshot.entries().size());
        try {
            cachedQuestsData = buildQuestsData(snapshot);
            log.info("Quest data refreshed: {} quests available",
                    cachedQuestsData.getAvailable() != null ? cachedQuestsData.getAvailable().size() : 0);
        } catch (Exception e) {
            log.error("Exception in buildQuestsData", e);
            cachedQuestsData = BotStatus.QuestsData.empty();
        }
    }

    /**
     * Build quest data from RuneLite's Quest API.
     * 
     * This provides real-time quest status by querying the game client directly
     * for each quest's state (NOT_STARTED, IN_PROGRESS, FINISHED).
     * 
     * Skill/quest requirement details are only populated for quests from our
     * QuestService (manual implementations), as RuneLite's Quest enum doesn't
     * expose requirement metadata directly.
     */
    private BotStatus.QuestsData buildQuestsData(QuestStateSnapshot snapshot) {
        List<BotStatus.QuestSummary> available = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        List<String> inProgress = new ArrayList<>();
        int totalQuestPoints = snapshot.questPoints();

        int processed = 0;
        for (QuestEntry entry : snapshot.entries()) {
            QuestState state = entry.state();

            if (state == QuestState.FINISHED) {
                completed.add(entry.id());
            } else if (state == QuestState.IN_PROGRESS) {
                inProgress.add(entry.id());
            }

            try {
                log.debug("Building summary for quest: {}", entry.id());
                BotStatus.QuestSummary summary = buildQuestSummary(entry);
                if (summary != null) {
                    available.add(summary);
                }
            } catch (Exception e) {
                log.debug("Failed to build summary for quest {}: {}", entry.id(), e.getMessage());
            }
            
            processed++;
            if (processed % 50 == 0) {
                log.debug("Processed {}/{} quests...", processed, snapshot.entries().size());
            }
        }

        log.debug("Finished processing all {} quests, building result", processed);
        return BotStatus.QuestsData.builder()
                .lastUpdated(System.currentTimeMillis())
                .available(available)
                .completed(completed)
                .inProgress(inProgress)
                .totalQuestPoints(totalQuestPoints)
                .build();
    }

    /**
     * Build a QuestSummary from pre-captured data.
     * Requirements were already fetched on the client thread - this just formats.
     */
    private BotStatus.QuestSummary buildQuestSummary(QuestEntry entry) {
        var reqStatus = entry.requirements();
        
        // If no requirements were captured, return basic info
        if (reqStatus == null) {
            return BotStatus.QuestSummary.builder()
                    .id(entry.id())
                    .name(entry.name())
                    .difficulty("Unknown")
                    .members(true)
                    .questPoints(0)
                    .state(entry.state() != null ? entry.state().name() : "NOT_STARTED")
                    .canStart(false)
                    .skillRequirements(Collections.emptyList())
                    .questRequirements(Collections.emptyList())
                    .itemRequirements(Collections.emptyList())
                    .build();
        }
        
        // Convert pre-captured requirements to BotStatus format
        List<BotStatus.SkillRequirementStatus> skillReqs = new ArrayList<>();
        for (var sr : reqStatus.getSkillRequirements()) {
            skillReqs.add(BotStatus.SkillRequirementStatus.builder()
                    .skill(sr.getSkillName())
                    .required(sr.getRequired())
                    .current(sr.getCurrent())
                    .met(sr.isMet())
                    .boostable(sr.isBoostable())
                    .build());
        }
        
        List<BotStatus.QuestRequirementStatus> questReqs = new ArrayList<>();
        for (var qr : reqStatus.getQuestRequirements()) {
            questReqs.add(BotStatus.QuestRequirementStatus.builder()
                    .questId(qr.getQuestId())
                    .questName(qr.getQuestName())
                    .met(qr.isMet())
                    .build());
        }
        
        List<BotStatus.ItemRequirementStatus> itemReqs = new ArrayList<>();
        for (var ir : reqStatus.getItemRequirements()) {
            itemReqs.add(BotStatus.ItemRequirementStatus.builder()
                    .itemId(ir.getItemId())
                    .itemName(ir.getName())
                    .quantity(ir.getQuantityRequired())
                    .inInventory(ir.getInInventory())
                    .equipped(ir.getEquipped())
                    .inBank(ir.getInBank())
                    .met(ir.isMet())
                    .obtainableDuringQuest(ir.isObtainableDuringQuest())
                    .recommended(ir.isRecommended())
                    .build());
        }
        
        return BotStatus.QuestSummary.builder()
                .id(reqStatus.getQuestId())
                .name(reqStatus.getQuestName())
                .difficulty(reqStatus.getDifficulty())
                .members(reqStatus.isMembers())
                .questPoints(reqStatus.getQuestPoints())
                .state(reqStatus.getState())
                .canStart(reqStatus.isCanStart())
                .skillRequirements(skillReqs)
                .questRequirements(questReqs)
                .itemRequirements(itemReqs)
                .build();
    }

    /**
     * Capture FULL quest state including requirements on the client thread.
     * All client access happens here so IO thread can safely format the data.
     */
    private QuestStateSnapshot captureQuestStateSnapshot() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return new QuestStateSnapshot(0, List.of());
        }

        int questPoints = 0;
        try {
            questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        } catch (Exception e) {
            log.debug("Could not read quest points: {}", e.getMessage());
        }

        List<QuestEntry> entries = new ArrayList<>();
        for (net.runelite.api.Quest quest : net.runelite.api.Quest.values()) {
            try {
                QuestState state = quest.getState(client);
                
                // Capture FULL requirements on client thread (this is the expensive part)
                RequirementStatus.QuestRequirementsStatus reqStatus = null;
                try {
                    reqStatus = questService.getRequirementsStatus(quest.name());
                } catch (Exception e) {
                    log.debug("Could not get requirements for {}: {}", quest.name(), e.getMessage());
                }
                
                entries.add(new QuestEntry(quest.name(), quest.getName(), quest, state, reqStatus));
            } catch (Exception e) {
                log.debug("Error getting state for quest {}: {}", quest.name(), e.getMessage());
            }
        }

        return new QuestStateSnapshot(questPoints, entries);
    }

    /**
     * Immutable snapshot of quest states captured on the client thread.
     */
    private record QuestStateSnapshot(int questPoints, List<QuestEntry> entries) {}

    /**
     * Quest entry with full requirements, captured on the client thread.
     */
    private record QuestEntry(
            String id, 
            String name, 
            net.runelite.api.Quest quest, 
            QuestState state,
            @Nullable RequirementStatus.QuestRequirementsStatus requirements
    ) {}

    /**
     * Write status if enough time has elapsed since last write.
     */
    private void writeStatusIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastWriteTime >= MIN_WRITE_INTERVAL_MS) {
            writeStatus();
        }
    }

    /**
     * Force write current status to file.
     */
    public void writeStatus() {
        try {
            BotStatus status = captureStatus();
            submitStatusWrite(status);
        } catch (Exception e) {
            log.error("Error capturing status", e);
        }
    }

    private void submitStatusWrite(BotStatus status) {
        log.debug("Submitting status write (io queue size={})", ioExecutor.getQueueSize());

        ioExecutor.submit(() -> {
            try {
                String json = status.toJson();
                Path tempPath = statusFilePath.resolveSibling(STATUS_FILE_NAME + ".tmp");
                Files.writeString(tempPath, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(tempPath, statusFilePath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);

                lastWriteTime = System.currentTimeMillis();
                lastStatus = status;
                log.debug("Status written to {}", statusFilePath);
            } catch (IOException e) {
                log.warn("Failed to write status file: {} - {}", statusFilePath, e.toString());
            }
        });
    }

    /**
     * Capture the current bot status.
     * 
     * @return status snapshot
     */
    private BotStatus captureStatus() {
        GameState gameState = client.getGameState();
        
        // Get current task
        Task currentTask = null;
        List<Task> pendingTasks = List.of();
        if (taskExecutor != null) {
            currentTask = taskExecutor.getCurrentTask();
            pendingTasks = taskExecutor.getPendingTasks();
        }

        // Get session stats
        SessionStats sessionStats = SessionStats.capture(xpTracker, fatigueModel, breakScheduler);

        // Get player state
        PlayerState playerState = gameStateServiceProvider.get().getPlayerState();

        // Get player name
        String playerName = null;
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            playerName = localPlayer.getName();
        }

        // Get quest points
        int questPoints = 0;
        try {
            questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        } catch (Exception e) {
            log.debug("Could not read quest points: {}", e.getMessage());
        }

        return BotStatus.capture(
                gameState,
                currentTask,
                sessionStats,
                playerState,
                xpTracker,
                playerName,
                questPoints,
                pendingTasks,
                cachedQuestsData
        );
    }

    /**
     * Get the path to the status file.
     * 
     * @return status file path
     */
    public Path getStatusFilePath() {
        return statusFilePath;
    }

    /**
     * Get the last captured status.
     * 
     * @return last status, or null if none
     */
    @Nullable
    public BotStatus getLastStatus() {
        return lastStatus;
    }

    /**
     * Set a custom status directory (for testing).
     * 
     * @param directory the directory path
     */
    public void setStatusDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            this.statusFilePath = directory.resolve(STATUS_FILE_NAME);
            log.info("Status directory changed to: {}", directory);
        } catch (IOException e) {
            log.error("Failed to set status directory: {}", directory, e);
        }
    }

    /**
     * Delete the status file (for cleanup).
     */
    public void deleteStatusFile() {
        try {
            Files.deleteIfExists(statusFilePath);
            log.debug("Status file deleted");
        } catch (IOException e) {
            log.warn("Failed to delete status file: {}", e.getMessage());
        }
    }

    /**
     * Get a summary of publisher state.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "StatusPublisher[enabled=%s, path=%s, lastWrite=%s]",
                enabled.get(),
                statusFilePath,
                lastWriteTime > 0 ? Instant.ofEpochMilli(lastWriteTime) : "never"
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

