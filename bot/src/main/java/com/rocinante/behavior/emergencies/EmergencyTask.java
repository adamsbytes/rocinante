package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.EmergencyHandler;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.TaskState;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Wrapper task for emergency response tasks.
 * 
 * This wrapper ensures that EmergencyHandler.emergencyResolved() is called
 * when the emergency response task completes, allowing cooldowns to clear
 * and the same emergency to retrigger if conditions persist.
 * 
 * Delegates all task execution to the wrapped response task.
 */
@Slf4j
public class EmergencyTask extends AbstractTask {

    private final Task responseTask;
    private final EmergencyHandler emergencyHandler;
    private final String emergencyId;

    /**
     * Create an emergency task wrapper.
     * 
     * @param responseTask the actual emergency response task (eat food, drink potion, etc.)
     * @param emergencyHandler the emergency handler to notify on completion
     * @param emergencyId the unique ID of this emergency
     */
    public EmergencyTask(Task responseTask, EmergencyHandler emergencyHandler, String emergencyId) {
        this.responseTask = responseTask;
        this.emergencyHandler = emergencyHandler;
        this.emergencyId = emergencyId;
        
        // Emergency tasks are always URGENT priority
        this.priority = TaskPriority.URGENT;
        
        // Use the wrapped task's timeout, or default to 10 seconds
        this.timeout = responseTask.getTimeout() != null ? responseTask.getTimeout() : Duration.ofSeconds(10);
    }

    @Override
    public String getDescription() {
        return "Emergency: " + responseTask.getDescription();
    }

    @Override
    public boolean canExecute(TaskContext context) {
        return responseTask.canExecute(context);
    }

    @Override
    protected void executeImpl(TaskContext context) {
        // Delegate to the wrapped task
        responseTask.execute(context);
        
        // Check if wrapped task is complete
        TaskState wrappedState = responseTask.getState();
        if (wrappedState == TaskState.COMPLETED) {
            complete();
        } else if (wrappedState == TaskState.FAILED) {
            fail("Wrapped emergency response task failed");
        }
        // Otherwise keep running
    }

    @Override
    public void onComplete(TaskContext context) {
        // Call wrapped task's completion handler
        responseTask.onComplete(context);
        
        // Notify emergency handler that this emergency is resolved
        if (emergencyHandler != null) {
            emergencyHandler.emergencyResolved(emergencyId);
            log.debug("Emergency resolved and reported to handler: {}", emergencyId);
        }
    }

    @Override
    public void onFail(TaskContext context, Exception e) {
        // Call wrapped task's failure handler
        responseTask.onFail(context, e);
        
        // Still notify handler even on failure (don't want emergency to stay "active" forever)
        if (emergencyHandler != null) {
            emergencyHandler.emergencyResolved(emergencyId);
            log.warn("Emergency task failed but marking as resolved to allow retrigger: {}", emergencyId);
        }
    }

    @Override
    public boolean isInterruptible() {
        // Emergency tasks should never be interrupted
        return false;
    }

    @Override
    public int getMaxRetries() {
        // Use wrapped task's retry count
        return responseTask.getMaxRetries();
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }
}

