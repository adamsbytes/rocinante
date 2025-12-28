package com.rocinante.status;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Processes commands from external sources via file-based communication.
 * 
 * Watches a commands.json file for new commands and dispatches them to
 * the appropriate handlers (TaskExecutor, etc.).
 * 
 * Command file format:
 * {
 *   "commands": [
 *     { "type": "START", "timestamp": 1234567890 },
 *     { "type": "STOP", "timestamp": 1234567890 },
 *     { "type": "QUEUE_TASK", "task": {...}, "timestamp": 1234567890 },
 *     { "type": "CLEAR_QUEUE", "timestamp": 1234567890 },
 *     { "type": "FORCE_BREAK", "timestamp": 1234567890 }
 *   ]
 * }
 */
@Slf4j
@Singleton
public class CommandProcessor {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Default commands file location within the RuneLite directory.
     */
    private static final String DEFAULT_STATUS_DIR = System.getProperty("user.home") 
            + "/.runelite/rocinante";
    private static final String COMMANDS_FILE_NAME = "commands.json";
    private static final String PROCESSED_FILE_NAME = "commands_processed.json";

    /**
     * Game ticks between file checks.
     */
    private static final int TICKS_BETWEEN_CHECKS = 3;

    // ========================================================================
    // Dependencies
    // ========================================================================

    @Setter
    @Nullable
    private TaskExecutor taskExecutor;

    @Setter
    @Nullable
    private TaskFactory taskFactory;

    @Setter
    @Nullable
    private CommandHandler commandHandler;

    @Setter
    @Nullable
    private StatusPublisher statusPublisher;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Path to the commands file.
     */
    private Path commandsFilePath;

    /**
     * Path to processed commands file (acknowledgment).
     */
    private Path processedFilePath;

    /**
     * Last modification time of commands file.
     */
    private volatile FileTime lastModifiedTime;

    /**
     * Timestamp of last processed command.
     */
    private volatile long lastProcessedTimestamp = 0;

    /**
     * Tick counter for periodic checks.
     */
    private int tickCounter = 0;

    /**
     * Whether processing is enabled.
     */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @Inject
    public CommandProcessor() {
        initializeCommandsDirectory();
        log.info("CommandProcessor initialized, watching: {}", commandsFilePath);
    }

    /**
     * Initialize the commands directory and file path.
     */
    private void initializeCommandsDirectory() {
        String statusDir = System.getenv("ROCINANTE_STATUS_DIR");
        if (statusDir == null || statusDir.isEmpty()) {
            statusDir = DEFAULT_STATUS_DIR;
        }

        Path dirPath = Paths.get(statusDir);
        try {
            Files.createDirectories(dirPath);
            commandsFilePath = dirPath.resolve(COMMANDS_FILE_NAME);
            processedFilePath = dirPath.resolve(PROCESSED_FILE_NAME);
            log.debug("Commands directory initialized: {}", dirPath);
        } catch (IOException e) {
            log.error("Failed to create commands directory: {}", dirPath, e);
            commandsFilePath = Paths.get(System.getProperty("java.io.tmpdir"), COMMANDS_FILE_NAME);
            processedFilePath = Paths.get(System.getProperty("java.io.tmpdir"), PROCESSED_FILE_NAME);
        }
    }

    /**
     * Start processing commands.
     */
    public void start() {
        enabled.set(true);
        log.info("Command processing started");
    }

    /**
     * Stop processing commands.
     */
    public void stop() {
        enabled.set(false);
        log.info("Command processing stopped");
    }

    /**
     * Check if processing is enabled.
     * 
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Handle game tick events to trigger periodic command file checks.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!enabled.get()) {
            return;
        }

        tickCounter++;
        if (tickCounter >= TICKS_BETWEEN_CHECKS) {
            tickCounter = 0;
            checkForCommands();
        }
    }

    /**
     * Check for new commands in the commands file.
     */
    private void checkForCommands() {
        if (!Files.exists(commandsFilePath)) {
            return;
        }

        try {
            FileTime currentModTime = Files.getLastModifiedTime(commandsFilePath);
            
            // Skip if file hasn't been modified
            if (lastModifiedTime != null && !currentModTime.toInstant().isAfter(lastModifiedTime.toInstant())) {
                return;
            }
            
            lastModifiedTime = currentModTime;
            processCommandsFile();
            
        } catch (IOException e) {
            log.warn("Error checking commands file: {}", e.getMessage());
        }
    }

    /**
     * Read and process commands from the file.
     */
    private void processCommandsFile() {
        try {
            String content = Files.readString(commandsFilePath, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return;
            }

            // Use older Gson API for compatibility with RuneLite's bundled Gson version
            JsonObject root = new JsonParser().parse(content).getAsJsonObject();
            JsonArray commands = root.getAsJsonArray("commands");
            
            if (commands == null || commands.isEmpty()) {
                return;
            }

            List<ProcessedCommand> processed = new ArrayList<>();
            
            for (JsonElement element : commands) {
                JsonObject cmdObj = element.getAsJsonObject();
                long timestamp = cmdObj.has("timestamp") ? cmdObj.get("timestamp").getAsLong() : 0;
                
                // Skip already processed commands
                if (timestamp <= lastProcessedTimestamp) {
                    continue;
                }

                String type = cmdObj.has("type") ? cmdObj.get("type").getAsString() : "UNKNOWN";
                boolean success = processCommand(type, cmdObj);
                
                processed.add(new ProcessedCommand(type, timestamp, success));
                lastProcessedTimestamp = Math.max(lastProcessedTimestamp, timestamp);
            }

            // Write acknowledgment
            if (!processed.isEmpty()) {
                writeProcessedAcknowledgment(processed);
            }

        } catch (Exception e) {
            log.error("Error processing commands file", e);
        }
    }

    /**
     * Process a single command.
     * 
     * @param type command type
     * @param cmdObj full command JSON object
     * @return true if successfully processed
     */
    private boolean processCommand(String type, JsonObject cmdObj) {
        log.info("Processing command: {}", type);

        try {
            switch (type.toUpperCase()) {
                case "START":
                    return handleStart();
                    
                case "STOP":
                    return handleStop();
                    
                case "CLEAR_QUEUE":
                    return handleClearQueue();
                    
                case "FORCE_BREAK":
                    return handleForceBreak();
                    
                case "ABORT_TASK":
                    return handleAbortTask();
                    
                case "QUEUE_TASK":
                    return handleQueueTask(cmdObj);

                case "REFRESH_QUESTS":
                    return handleRefreshQuests();
                    
                default:
                    // Delegate to custom handler if available
                    if (commandHandler != null) {
                        return commandHandler.handleCommand(type, cmdObj);
                    }
                    log.warn("Unknown command type: {}", type);
                    return false;
            }
        } catch (Exception e) {
            log.error("Error processing command {}: {}", type, e.getMessage());
            return false;
        }
    }

    private boolean handleStart() {
        if (taskExecutor != null) {
            taskExecutor.start();
            return true;
        }
        return false;
    }

    private boolean handleStop() {
        if (taskExecutor != null) {
            taskExecutor.stop();
            return true;
        }
        return false;
    }

    private boolean handleClearQueue() {
        if (taskExecutor != null) {
            taskExecutor.clearQueue();
            return true;
        }
        return false;
    }

    private boolean handleAbortTask() {
        if (taskExecutor != null) {
            taskExecutor.abortCurrentTask();
            return true;
        }
        return false;
    }

    private boolean handleForceBreak() {
        // This would integrate with BreakScheduler to force a break
        // For now, just stop the executor briefly
        if (taskExecutor != null) {
            log.info("Force break requested - pausing execution");
            // Could queue a break task here
            return true;
        }
        return false;
    }

    private boolean handleRefreshQuests() {
        if (statusPublisher != null) {
            log.info("Quest data refresh requested from UI");
            statusPublisher.requestQuestRefresh();
            return true;
        }
        log.warn("Cannot refresh quests: StatusPublisher not set");
        return false;
    }

    private boolean handleQueueTask(JsonObject cmdObj) {
        if (taskExecutor == null) {
            log.warn("Cannot queue task: TaskExecutor not set");
            return false;
        }
        
        if (taskFactory == null) {
            log.warn("Cannot queue task: TaskFactory not set");
            return false;
        }

        // Extract task spec from command
        if (!cmdObj.has("task")) {
            log.warn("Queue task command missing 'task' field");
            return false;
        }

        JsonObject taskSpec = cmdObj.getAsJsonObject("task");
        
        // Parse priority if provided
        TaskPriority priority = TaskPriority.NORMAL;
        if (cmdObj.has("priority")) {
            try {
                priority = TaskPriority.valueOf(cmdObj.get("priority").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid task priority, using NORMAL");
            }
        }

        // Create task using factory
        Optional<Task> taskOpt = taskFactory.createTask(taskSpec);
        if (taskOpt.isEmpty()) {
            log.warn("Failed to create task from spec: {}", taskSpec);
            return false;
        }

        Task task = taskOpt.get();
        taskExecutor.queueTask(task, priority);
        log.info("Queued task: {} (priority: {})", task.getDescription(), priority);
        return true;
    }

    /**
     * Write acknowledgment of processed commands.
     */
    private void writeProcessedAcknowledgment(List<ProcessedCommand> processed) {
        try {
            JsonObject ack = new JsonObject();
            ack.addProperty("lastProcessed", lastProcessedTimestamp);
            ack.addProperty("processedAt", System.currentTimeMillis());
            
            JsonArray processedArray = new JsonArray();
            for (ProcessedCommand cmd : processed) {
                JsonObject cmdAck = new JsonObject();
                cmdAck.addProperty("type", cmd.type);
                cmdAck.addProperty("timestamp", cmd.timestamp);
                cmdAck.addProperty("success", cmd.success);
                processedArray.add(cmdAck);
            }
            ack.add("commands", processedArray);
            
            Files.writeString(processedFilePath, GSON.toJson(ack), StandardCharsets.UTF_8);
            log.debug("Wrote processed acknowledgment for {} commands", processed.size());
            
        } catch (IOException e) {
            log.warn("Failed to write processed acknowledgment: {}", e.getMessage());
        }
    }

    /**
     * Get the path to the commands file.
     * 
     * @return commands file path
     */
    public Path getCommandsFilePath() {
        return commandsFilePath;
    }

    /**
     * Set a custom commands directory (for testing).
     * 
     * @param directory the directory path
     */
    public void setCommandsDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            this.commandsFilePath = directory.resolve(COMMANDS_FILE_NAME);
            this.processedFilePath = directory.resolve(PROCESSED_FILE_NAME);
            log.info("Commands directory changed to: {}", directory);
        } catch (IOException e) {
            log.error("Failed to set commands directory: {}", directory, e);
        }
    }

    /**
     * Clear processed state (for testing).
     */
    public void reset() {
        lastModifiedTime = null;
        lastProcessedTimestamp = 0;
        tickCounter = 0;
    }

    /**
     * Get a summary of processor state.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "CommandProcessor[enabled=%s, path=%s, lastProcessed=%d]",
                enabled.get(),
                commandsFilePath,
                lastProcessedTimestamp
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Record of a processed command.
     */
    @Value
    private static class ProcessedCommand {
        String type;
        long timestamp;
        boolean success;
    }

    /**
     * Interface for custom command handlers.
     */
    public interface CommandHandler {
        /**
         * Handle a custom command.
         * 
         * @param type command type
         * @param cmdObj full command JSON object
         * @return true if successfully handled
         */
        boolean handleCommand(String type, JsonObject cmdObj);
    }
}

