package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.LogoutHandler;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskState;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Session end task - executes humanized logout and pauses the task executor.
 * 
 * Per REQUIREMENTS.md Section 4.3:
 * - Session end: After 2-6 hours (mandatory logout)
 * 
 * This task:
 * 1. Records the logout in player profile
 * 2. Executes humanized logout via LogoutHandler
 * 3. Stops the task executor (manual resume required)
 */
@Slf4j
public class SessionEndTask extends BehavioralTask {

    private final BreakScheduler breakScheduler;
    private final LogoutHandler logoutHandler;
    private final PlayerProfile playerProfile;
    private final TaskExecutor taskExecutor;
    
    private Instant startTime;
    private boolean started = false;
    private final AtomicBoolean logoutInitiated = new AtomicBoolean(false);
    private CompletableFuture<Boolean> logoutFuture;

    /**
     * Create a session end task.
     * 
     * @param breakScheduler the break scheduler (for completion callback)
     * @param logoutHandler the logout handler for humanized logout
     * @param playerProfile the player profile for recording logout
     * @param taskExecutor the task executor to stop after logout
     */
    public SessionEndTask(BreakScheduler breakScheduler,
                          LogoutHandler logoutHandler,
                          PlayerProfile playerProfile,
                          TaskExecutor taskExecutor) {
        super(BreakType.SESSION_END, Duration.ofMinutes(2)); // Allow time for logout
        this.breakScheduler = breakScheduler;
        this.logoutHandler = logoutHandler;
        this.playerProfile = playerProfile;
        this.taskExecutor = taskExecutor;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!started) {
            startTime = Instant.now();
            started = true;
            log.info("Session end triggered - initiating humanized logout");
        }
        
        // Initiate logout if not already started
        if (logoutInitiated.compareAndSet(false, true)) {
            log.debug("Starting logout sequence");
            logoutFuture = logoutHandler.executeLogout(LogoutHandler.LogoutContext.NORMAL);
        }
        
        // Check logout completion
        if (logoutFuture != null && logoutFuture.isDone()) {
            try {
                boolean success = logoutFuture.get();
                if (success) {
                    log.info("Session end logout completed successfully");
                } else {
                    log.warn("Session end logout returned false - may not have logged out");
                }
            } catch (Exception e) {
                log.error("Session end logout failed with exception", e);
            }
            
            // Stop the task executor regardless of logout success
            // User must manually restart automation for next session
            log.info("Stopping task executor for session end");
            taskExecutor.stop();
            
            transitionTo(TaskState.COMPLETED);
        }
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        log.info("Session end completed after {} seconds - automation stopped", 
                actualDuration.toSeconds());
        
        // Report break completion to scheduler
        if (breakScheduler != null) {
            breakScheduler.onBreakCompleted(BreakType.SESSION_END, actualDuration);
        }
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        log.warn("Session end task failed: {}", e != null ? e.getMessage() : "unknown error");
        
        // Still try to stop the executor on failure
        log.info("Stopping task executor despite session end failure");
        taskExecutor.stop();
    }

    @Override
    public String getDescription() {
        return "Session end - logout and stop automation";
    }
}

