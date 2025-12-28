package com.rocinante.status;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.core.GameStateService;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskExecutor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
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
     * Default status file location within the RuneLite directory.
     */
    private static final String DEFAULT_STATUS_DIR = System.getProperty("user.home") 
            + "/.runelite/rocinante";
    private static final String STATUS_FILE_NAME = "status.json";

    /**
     * Minimum interval between status writes (milliseconds).
     */
    private static final long MIN_WRITE_INTERVAL_MS = 1000;

    /**
     * Game ticks between status updates (roughly 1 second = ~1.67 ticks).
     */
    private static final int TICKS_BETWEEN_UPDATES = 2;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final GameStateService gameStateService;
    
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

    // ========================================================================
    // State
    // ========================================================================

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
    public StatusPublisher(Client client, GameStateService gameStateService) {
        this.client = client;
        this.gameStateService = gameStateService;
        
        // Initialize status directory
        initializeStatusDirectory();
        
        log.info("StatusPublisher initialized, output: {}", statusFilePath);
    }

    /**
     * Initialize the status directory and file path.
     */
    private void initializeStatusDirectory() {
        String statusDir = System.getenv("ROCINANTE_STATUS_DIR");
        if (statusDir == null || statusDir.isEmpty()) {
            statusDir = DEFAULT_STATUS_DIR;
        }

        Path dirPath = Paths.get(statusDir);
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

        tickCounter++;
        if (tickCounter >= TICKS_BETWEEN_UPDATES) {
            tickCounter = 0;
            writeStatusIfNeeded();
        }
    }

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
            String json = status.toJson();
            
            // Write atomically by writing to temp file first
            Path tempPath = statusFilePath.resolveSibling(STATUS_FILE_NAME + ".tmp");
            Files.writeString(tempPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempPath, statusFilePath, 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            
            lastWriteTime = System.currentTimeMillis();
            lastStatus = status;
            
            log.trace("Status written to {}", statusFilePath);
        } catch (IOException e) {
            log.warn("Failed to write status file: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error capturing status", e);
        }
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
        PlayerState playerState = gameStateService.getPlayerState();

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
            log.trace("Could not read quest points: {}", e.getMessage());
        }

        return BotStatus.capture(
                gameState,
                currentTask,
                sessionStats,
                playerState,
                xpTracker,
                playerName,
                questPoints,
                pendingTasks
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

