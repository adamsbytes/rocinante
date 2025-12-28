package com.rocinante.tasks;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.EmergencyHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Task queue management and execution engine.
 *
 * Per REQUIREMENTS.md Section 5.5:
 * <ul>
 *   <li>FIFO queue with priority support (URGENT, NORMAL, LOW)</li>
 *   <li>URGENT interrupts current task (for combat reactions, death handling)</li>
 *   <li>Failed tasks trigger configurable retry policy: immediate retry,
 *       exponential backoff, or abort</li>
 *   <li>Idle task injected when queue empty</li>
 * </ul>
 *
 * <p>Retry Policy (per Section 5.1):
 * Automatic retry with exponential backoff: 1s -> 2s -> 4s, max 3 retries
 */
@Slf4j
@Singleton
public class TaskExecutor {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Base retry delay in milliseconds.
     */
    private static final long BASE_RETRY_DELAY_MS = 1000;

    /**
     * Exponential backoff multiplier.
     */
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

    /**
     * Maximum queue size to prevent memory issues.
     */
    private static final int MAX_QUEUE_SIZE = 1000;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final TaskContext taskContext;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Priority queue for pending tasks.
     * Higher priority (lower ordinal) tasks are processed first.
     * Within same priority, FIFO order is maintained via sequence number.
     */
    private final PriorityBlockingQueue<QueuedTask> taskQueue;

    /**
     * Sequence counter for FIFO ordering within same priority.
     * Uses AtomicLong for thread-safe increments during concurrent task queuing.
     */
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    /**
     * The currently executing task.
     */
    @Getter
    private volatile Task currentTask;

    /**
     * Number of retry attempts for current task.
     */
    private int currentRetryCount = 0;

    /**
     * Timestamp of last retry for backoff calculation.
     */
    private Instant lastRetryTime;

    /**
     * Master enabled flag.
     */
    @Getter
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Flag indicating an urgent task is pending.
     */
    private volatile boolean urgentPending = false;

    /**
     * Callback for stuck task detection.
     */
    private Consumer<Task> onTaskStuckCallback;

    /**
     * Optional idle task supplier for when queue is empty.
     */
    private java.util.function.Supplier<Task> idleTaskSupplier;

    /**
     * Break scheduler for behavioral task injection.
     */
    @Setter
    @Nullable
    private BreakScheduler breakScheduler;

    /**
     * Emergency handler for urgent interrupt injection.
     */
    @Setter
    @Nullable
    private EmergencyHandler emergencyHandler;

    /**
     * Stack of tasks paused for behavioral interruptions (to resume after).
     * Using a stack allows nested behavioral breaks to preserve all paused tasks.
     */
    private final Deque<Task> pausedTaskStack = new LinkedList<>();

    /**
     * Whether the current step of the task is complete (for behavioral interrupts).
     */
    @Getter
    @Setter
    private volatile boolean currentStepComplete = true;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public TaskExecutor(TaskContext taskContext) {
        this.taskContext = taskContext;
        this.taskQueue = new PriorityBlockingQueue<>(100, Comparator
                .comparingInt((QueuedTask qt) -> qt.task.getPriority().getOrdinalValue())
                .thenComparingLong(qt -> qt.sequence));
        log.info("TaskExecutor initialized");
    }

    // ========================================================================
    // Queue Management
    // ========================================================================

    /**
     * Add a task to the queue with NORMAL priority.
     *
     * @param task the task to queue
     */
    public void queueTask(Task task) {
        queueTask(task, TaskPriority.NORMAL);
    }

    /**
     * Add a task to the queue with specified priority.
     *
     * @param task     the task to queue
     * @param priority the priority level
     */
    public void queueTask(Task task, TaskPriority priority) {
        if (task == null) {
            log.warn("Attempted to queue null task");
            return;
        }

        if (taskQueue.size() >= MAX_QUEUE_SIZE) {
            log.error("Task queue is full ({} tasks), rejecting: {}",
                    MAX_QUEUE_SIZE, task.getDescription());
            return;
        }

        // Set priority on task if it's an AbstractTask
        if (task instanceof AbstractTask) {
            ((AbstractTask) task).setPriority(priority);
        }

        QueuedTask queuedTask = new QueuedTask(task, sequenceCounter.getAndIncrement());
        taskQueue.offer(queuedTask);

        // Track urgent tasks for interrupt handling
        if (priority == TaskPriority.URGENT) {
            urgentPending = true;
        }

        log.debug("Queued task: {} (priority: {}, queue size: {})",
                task.getDescription(), priority, taskQueue.size());
    }

    /**
     * Add multiple tasks to the queue.
     *
     * @param tasks the tasks to queue
     */
    public void queueTasks(List<Task> tasks) {
        for (Task task : tasks) {
            queueTask(task);
        }
    }

    /**
     * Clear all pending tasks from the queue.
     *
     * @return the number of tasks cleared
     */
    public int clearQueue() {
        int count = taskQueue.size();
        taskQueue.clear();
        urgentPending = false;
        log.info("Cleared {} tasks from queue", count);
        return count;
    }

    /**
     * Get the number of pending tasks.
     *
     * @return queue size
     */
    public int getQueueSize() {
        return taskQueue.size();
    }

    /**
     * Check if there are pending tasks.
     *
     * @return true if queue is not empty
     */
    public boolean hasPendingTasks() {
        return !taskQueue.isEmpty();
    }

    /**
     * Get a copy of all pending tasks (for debugging).
     *
     * @return list of pending tasks
     */
    public List<Task> getPendingTasks() {
        List<Task> tasks = new ArrayList<>();
        for (QueuedTask qt : taskQueue) {
            tasks.add(qt.task);
        }
        return tasks;
    }

    // ========================================================================
    // Execution Control
    // ========================================================================

    /**
     * Enable task execution.
     */
    public void start() {
        enabled.set(true);
        log.info("TaskExecutor started");
    }

    /**
     * Disable task execution and abort current task.
     */
    public void stop() {
        enabled.set(false);
        abortCurrentTask();
        log.info("TaskExecutor stopped");
    }

    /**
     * Abort the currently executing task.
     */
    public void abortCurrentTask() {
        if (currentTask != null) {
            log.info("Aborting current task: {}", currentTask.getDescription());
            if (currentTask instanceof AbstractTask) {
                ((AbstractTask) currentTask).abort();
            }
            handleTaskCompletion(false);
        }
    }

    /**
     * Set callback for stuck task detection.
     * Called before task failure due to timeout.
     *
     * @param callback the callback
     */
    public void setOnTaskStuckCallback(Consumer<Task> callback) {
        this.onTaskStuckCallback = callback;
    }

    /**
     * Set the idle task supplier.
     * Called when queue is empty to inject idle behavior.
     *
     * @param supplier the idle task supplier
     */
    public void setIdleTaskSupplier(java.util.function.Supplier<Task> supplier) {
        this.idleTaskSupplier = supplier;
    }

    // ========================================================================
    // Main Tick Handler
    // ========================================================================

    /**
     * Main execution tick - called once per game tick.
     * Processes the task queue and executes the current task.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!enabled.get()) {
            return;
        }

        // Check for abort request from context
        if (taskContext.isAbortRequested()) {
            log.debug("Abort requested via TaskContext");
            abortCurrentTask();
            taskContext.clearAbort();
        }

        // Check for emergencies first (highest priority)
        checkForEmergencies();

        // Handle urgent task interruption
        if (urgentPending && currentTask != null && currentTask.isInterruptible()) {
            QueuedTask urgent = peekUrgent();
            if (urgent != null) {
                log.info("Interrupting task '{}' for urgent task '{}'",
                        currentTask.getDescription(), urgent.task.getDescription());
                
                // Special handling for behavioral task interruption
                // Preserve the paused task stack by NOT completing the behavioral task normally
                if (currentTask.getPriority() == TaskPriority.BEHAVIORAL) {
                    log.debug("URGENT interrupted BEHAVIORAL task - preserving pause stack");
                    // Just cancel the behavioral task - pause stack remains intact
                    currentTask = null;
                    // Continue to pick up the urgent task below (don't return)
                } else {
                    // Re-queue current task if it's still runnable
                    if (currentTask.getState() == TaskState.RUNNING) {
                        requeueCurrentTask();
                    }
                    currentTask = null;
                }
                urgentPending = checkForMoreUrgent();
            }
        }

        // Check for scheduled breaks (if current step is complete)
        if (currentStepComplete) {
            checkForScheduledBreaks();
        }

        // Execute current task or get next one
        if (currentTask == null || currentTask.getState().isTerminal()) {
            if (currentTask != null) {
                handleTaskCompletion(currentTask.getState() == TaskState.COMPLETED);
            }
            currentTask = getNextTask();
        }

        if (currentTask != null) {
            executeCurrentTask();
        }
    }

    /**
     * Check for emergency conditions and queue response tasks.
     */
    private void checkForEmergencies() {
        if (emergencyHandler == null) {
            return;
        }

        emergencyHandler.checkEmergencies(taskContext).ifPresent(emergencyTask -> {
            log.warn("Queuing emergency task: {}", emergencyTask.getDescription());
            queueTask(emergencyTask, TaskPriority.URGENT);
        });
    }

    /**
     * Check for scheduled breaks and queue behavioral tasks.
     */
    private void checkForScheduledBreaks() {
        if (breakScheduler == null) {
            return;
        }

        // Don't schedule breaks if there's already a behavioral task queued
        if (hasBehavioralTaskQueued()) {
            return;
        }

        breakScheduler.getScheduledBreak().ifPresent(breakTask -> {
            log.debug("Queuing behavioral break task: {}", breakTask.getDescription());
            
            // Pause current task if running (stack handles nesting)
            if (currentTask != null && currentTask.getState() == TaskState.RUNNING) {
                pauseCurrentTask();
            }
            
            queueTask(breakTask, TaskPriority.BEHAVIORAL);
        });
    }

    /**
     * Check if there's already a behavioral task in the queue (not including current).
     * Allows behavioral task nesting - current behavioral can be paused for new one.
     */
    private boolean hasBehavioralTaskQueued() {
        for (QueuedTask qt : taskQueue) {
            if (qt.task.getPriority() == TaskPriority.BEHAVIORAL) {
                return true;
            }
        }
        // Don't block nesting - allow new behavioral to pause current behavioral
        return false;
    }

    /**
     * Pause the current task to run a behavioral task.
     * The paused task will be resumed after the behavioral task completes.
     * Using a stack allows nested behavioral interruptions.
     */
    private void pauseCurrentTask() {
        if (currentTask != null && currentTask.getState() == TaskState.RUNNING) {
            log.debug("Pausing task for behavioral interrupt: {}", currentTask.getDescription());
            
            // Push current task onto the stack
            pausedTaskStack.push(currentTask);
            
            // Transition to PAUSED state
            if (currentTask instanceof AbstractTask) {
                ((AbstractTask) currentTask).transitionTo(TaskState.PAUSED);
            }
            currentTask = null;
        }
    }

    /**
     * Execute one tick of the current task.
     */
    private void executeCurrentTask() {
        try {
            // Check for timeout and invoke stuck callback
            if (currentTask instanceof AbstractTask) {
                AbstractTask at = (AbstractTask) currentTask;
                if (at.isTimedOut() && onTaskStuckCallback != null) {
                    onTaskStuckCallback.accept(currentTask);
                }
            }

            // Mark step as incomplete while executing
            currentStepComplete = false;

            // Execute the task
            currentTask.execute(taskContext);
            
            // Record action for fatigue/break tracking
            // Each task tick counts as an action for behavioral modeling
            recordActionIfNeeded();

            // Check if task is now complete or failed
            TaskState state = currentTask.getState();
            if (state == TaskState.COMPLETED) {
                currentTask.onComplete(taskContext);
                handleTaskCompletion(true);
            } else if (state == TaskState.FAILED) {
                currentTask.onFail(taskContext, null);
                handleTaskFailure();
            }

        } catch (Exception e) {
            log.error("Exception executing task: {}", currentTask.getDescription(), e);
            currentTask.onFail(taskContext, e);
            handleTaskFailure();
        }
    }
    
    /**
     * Record action for fatigue and break tracking.
     * Called each task tick. Behavioral tasks don't count as actions.
     */
    private void recordActionIfNeeded() {
        // Don't record actions for behavioral tasks (breaks, rituals, etc.)
        if (currentTask != null && currentTask.getPriority() == TaskPriority.BEHAVIORAL) {
            return;
        }
        
        taskContext.recordAction();
    }

    // ========================================================================
    // Task Lifecycle
    // ========================================================================

    /**
     * Get the next task to execute.
     */
    private Task getNextTask() {
        // Check retry backoff
        if (lastRetryTime != null) {
            long backoffMs = calculateBackoffMs(currentRetryCount);
            Duration elapsed = Duration.between(lastRetryTime, Instant.now());
            if (elapsed.toMillis() < backoffMs) {
                // Still in backoff period
                return null;
            }
            lastRetryTime = null;
        }

        // Poll next task from queue
        QueuedTask next = taskQueue.poll();
        if (next != null) {
            if (next.task.getPriority() == TaskPriority.URGENT) {
                urgentPending = checkForMoreUrgent();
            }
            log.debug("Starting task: {}", next.task.getDescription());
            return next.task;
        }

        // Queue is empty - try idle task
        if (idleTaskSupplier != null) {
            Task idleTask = idleTaskSupplier.get();
            if (idleTask != null) {
                log.trace("Injecting idle task: {}", idleTask.getDescription());
                return idleTask;
            }
        }

        return null;
    }

    /**
     * Handle task completion (success or failure after retries exhausted).
     *
     * @param success true if task completed successfully
     */
    private void handleTaskCompletion(boolean success) {
        if (success) {
            log.debug("Task completed successfully: {}",
                    currentTask != null ? currentTask.getDescription() : "null");
        }
        
        // Check if we have a paused task to resume after behavioral/urgent task completes
        if (currentTask != null && 
            (currentTask.getPriority() == TaskPriority.BEHAVIORAL || 
             currentTask.getPriority() == TaskPriority.URGENT)) {
            if (!pausedTaskStack.isEmpty()) {
                Task pausedTask = pausedTaskStack.pop();
                log.debug("Resuming paused task after {} priority task: {}", 
                        currentTask.getPriority(), pausedTask.getDescription());
                // Re-queue the paused task to resume it
                queueTask(pausedTask, pausedTask.getPriority());
            }
        }
        
        currentTask = null;
        currentRetryCount = 0;
        lastRetryTime = null;
        
        // Reset step completion flag to allow breaks between tasks
        currentStepComplete = true;
    }

    /**
     * Handle task failure with retry logic.
     */
    private void handleTaskFailure() {
        if (currentTask == null) {
            return;
        }

        int maxRetries = currentTask.getMaxRetries();
        currentRetryCount++;

        if (currentRetryCount <= maxRetries) {
            // Schedule retry with exponential backoff
            lastRetryTime = Instant.now();
            long backoffMs = calculateBackoffMs(currentRetryCount);
            log.info("Task '{}' failed, retry {}/{} in {}ms",
                    currentTask.getDescription(), currentRetryCount, maxRetries, backoffMs);

            // Reset task state if possible
            if (currentTask instanceof AbstractTask) {
                // Create a new instance for retry would be better,
                // but for now we'll keep the same task
                ((AbstractTask) currentTask).state = TaskState.PENDING;
                ((AbstractTask) currentTask).startTime = null;
                ((AbstractTask) currentTask).executionTicks = 0;
            }
        } else {
            log.warn("Task '{}' failed after {} retries, abandoning",
                    currentTask.getDescription(), maxRetries);
            handleTaskCompletion(false);
        }
    }

    /**
     * Calculate backoff delay for retry attempt.
     */
    private long calculateBackoffMs(int retryCount) {
        // Exponential backoff: 1s, 2s, 4s, ...
        return (long) (BASE_RETRY_DELAY_MS * Math.pow(RETRY_BACKOFF_MULTIPLIER, retryCount - 1));
    }

    /**
     * Re-queue the current task (for interruption handling).
     */
    private void requeueCurrentTask() {
        if (currentTask != null && currentTask.getState() == TaskState.RUNNING) {
            // Reset state to PENDING
            if (currentTask instanceof AbstractTask) {
                ((AbstractTask) currentTask).state = TaskState.PENDING;
            }
            queueTask(currentTask, currentTask.getPriority());
            log.debug("Re-queued interrupted task: {}", currentTask.getDescription());
        }
    }

    /**
     * Peek at the next urgent task without removing it.
     */
    private QueuedTask peekUrgent() {
        for (QueuedTask qt : taskQueue) {
            if (qt.task.getPriority() == TaskPriority.URGENT) {
                return qt;
            }
        }
        return null;
    }

    /**
     * Check if there are more urgent tasks in queue.
     */
    private boolean checkForMoreUrgent() {
        for (QueuedTask qt : taskQueue) {
            if (qt.task.getPriority() == TaskPriority.URGENT) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // Diagnostics
    // ========================================================================

    /**
     * Get current executor status for debugging.
     *
     * @return status string
     */
    public String getStatus() {
        return String.format(
                "TaskExecutor[enabled=%s, current=%s, queue=%d, retries=%d]",
                enabled.get(),
                currentTask != null ? currentTask.getDescription() : "none",
                taskQueue.size(),
                currentRetryCount
        );
    }

    /**
     * Get the current task's state.
     *
     * @return Optional containing the current task state
     */
    public Optional<TaskState> getCurrentTaskState() {
        return Optional.ofNullable(currentTask).map(Task::getState);
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Wrapper for queued tasks with sequence number for FIFO ordering.
     */
    private static class QueuedTask {
        final Task task;
        final long sequence;

        QueuedTask(Task task, long sequence) {
            this.task = task;
            this.sequence = sequence;
        }
    }
}

