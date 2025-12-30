package com.rocinante.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.util.IoExecutor;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * File-driven command processing is a critical integration point for the bot/web bridge.
 * These tests use a temp directory and an immediate executor to ensure commands are read,
 * dispatched, and acknowledged without relying on background threads.
 */
public class CommandProcessorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private TaskFactory taskFactory;

    @Mock
    private StatusPublisher statusPublisher;

    @Mock
    private ClientThread clientThread;

    private AutoCloseable mocks;
    private CommandProcessor processor;

    @Before
    public void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);

        // Run client thread callbacks immediately
        org.mockito.Mockito.doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));

        processor = new CommandProcessor(new DirectIoExecutor(), clientThread);
        processor.setTaskExecutor(taskExecutor);
        processor.setTaskFactory(taskFactory);
        processor.setStatusPublisher(statusPublisher);
        processor.setCommandsDirectory(temp.getRoot().toPath());
        processor.reset();
    }

    @After
    public void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void startCommandIsProcessedAndAcknowledged() throws Exception {
        writeCommandsJson(command("START", 1));

        processor.start();
        tickTimes(3);

        verify(taskExecutor).start();

        JsonObject ack = readProcessedAck();
        assertEquals(1, ack.get("lastProcessed").getAsLong());
        // Re-run ticks with same file should not reprocess
        tickTimes(2);
        verify(taskExecutor).start(); // still only once
    }

    @Test
    public void queueTaskCommandCreatesTaskAndQueues() throws Exception {
        Task stubTask = new StubTask("stub task");
        when(taskFactory.createTask(any())).thenReturn(Optional.of(stubTask));

        JsonObject queueCmd = command("QUEUE_TASK", 5);
        JsonObject taskSpec = new JsonObject();
        taskSpec.addProperty("taskType", "NAVIGATION");
        queueCmd.add("task", taskSpec);
        queueCmd.addProperty("priority", "URGENT");

        writeCommandsJson(queueCmd);

        processor.start();
        tickTimes(3);

        verify(taskFactory).createTask(any());
        verify(taskExecutor).queueTask(eq(stubTask), eq(TaskPriority.URGENT));

        JsonObject ack = readProcessedAck();
        assertEquals(5, ack.get("lastProcessed").getAsLong());
    }

    @Test
    public void refreshQuestsCommandTriggersPublisher() throws Exception {
        writeCommandsJson(command("REFRESH_QUESTS", 10));

        processor.start();
        tickTimes(3);

        verify(statusPublisher).requestQuestRefresh();
    }

    private void tickTimes(int times) {
        for (int i = 0; i < times; i++) {
            processor.onGameTick(new GameTick());
        }
    }

    private JsonObject command(String type, long timestamp) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("timestamp", timestamp);
        return obj;
    }

    private void writeCommandsJson(JsonObject... commands) throws IOException {
        JsonArray arr = new JsonArray();
        for (JsonObject c : commands) {
            arr.add(c);
        }
        JsonObject root = new JsonObject();
        root.add("commands", arr);
        Path commandsPath = temp.getRoot().toPath().resolve("commands.json");
        Files.writeString(commandsPath, root.toString(), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(commandsPath, java.nio.file.attribute.FileTime.from(Instant.now()));
    }

    private JsonObject readProcessedAck() throws IOException {
        Path processedPath = temp.getRoot().toPath().resolve("commands_processed.json");
        String content = Files.readString(processedPath, StandardCharsets.UTF_8);
        return com.google.gson.JsonParser.parseString(content).getAsJsonObject();
    }

    /**
     * Immediate executor so supplyAsync + submits run synchronously in tests.
     */
    private static class DirectIoExecutor extends IoExecutor {
        private final ImmediateExecutorService direct = new ImmediateExecutorService();

        @Override
        public java.util.concurrent.ExecutorService getExecutor() {
            return direct;
        }

        @Override
        public <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
            java.util.concurrent.FutureTask<T> future = new java.util.concurrent.FutureTask<>(task);
            future.run();
            return future;
        }

        @Override
        public Future<?> submit(Runnable task) {
            java.util.concurrent.FutureTask<?> future = new java.util.concurrent.FutureTask<>(task, null);
            future.run();
            return future;
        }

        private static class ImmediateExecutorService extends AbstractExecutorService {
            private volatile boolean shutdown = false;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        }
    }

    private static class StubTask implements Task {
        private final String desc;

        StubTask(String desc) {
            this.desc = desc;
        }

        @Override
        public TaskState getState() {
            return TaskState.PENDING;
        }

        @Override
        public boolean canExecute(com.rocinante.tasks.TaskContext ctx) {
            return true;
        }

        @Override
        public void execute(com.rocinante.tasks.TaskContext ctx) {
        }

        @Override
        public void onComplete(com.rocinante.tasks.TaskContext ctx) {
        }

        @Override
        public void onFail(com.rocinante.tasks.TaskContext ctx, Exception e) {
        }

        @Override
        public String getDescription() {
            return desc;
        }
    }
}


