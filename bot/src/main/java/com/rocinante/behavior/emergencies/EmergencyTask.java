package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.EmergencyHandler;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.TaskState;
import lombok.extern.slf4j.Slf4j;

/**
 * Wrapper task for emergency response tasks.
 * 
 * This wrapper ensures that EmergencyHandler.emergencySucceeded/Failed() is called
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
        
        // Notify emergency handler that this emergency succeeded - clear cooldown
        if (emergencyHandler != null) {
            emergencyHandler.emergencySucceeded(emergencyId);
            log.debug("Emergency succeeded and reported to handler: {}", emergencyId);
        }
    }

    @Override
    public void onFail(TaskContext context, Exception e) {
        // Call wrapped task's failure handler
        responseTask.onFail(context, e);
        
        // Notify handler of failure - keeps cooldown to prevent spam retriggers
        if (emergencyHandler != null) {
            emergencyHandler.emergencyFailed(emergencyId);
            log.warn("Emergency task failed, cooldown retained to prevent spam: {}", emergencyId);
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
}

