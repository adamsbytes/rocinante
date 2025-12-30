package com.rocinante.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Single-thread executor dedicated to disk/network I/O.
 * All off-client-thread work should be funneled here to avoid blocking GameTick.
 */
@Slf4j
@Singleton
public class IoExecutor {

    private final ExecutorService executor;

    public IoExecutor() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "Rocinante-IO");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
        log.info("IoExecutor initialized");
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    /**
     * Debug helper: current queued task count.
     */
    public int getQueueSize() {
        if (executor instanceof ThreadPoolExecutor tpe) {
            return tpe.getQueue().size();
        }
        return 0;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

