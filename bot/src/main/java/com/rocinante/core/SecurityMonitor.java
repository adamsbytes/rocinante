package com.rocinante.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitors for debugging/tracing attempts that could indicate analysis.
 * 
 * Detection methods:
 * 1. TracerPid check - reads /proc/self/status for non-zero TracerPid
 * 2. ptrace(PTRACE_TRACEME) - fails if already being traced
 * 3. Timing anomaly detection - syscall interception causes slowdowns
 * 
 * When detection occurs, triggers graceful shutdown via callback.
 * 
 * Note: This is defensive, not aggressive. We simply exit if being analyzed,
 * rather than trying to interfere with the analysis.
 */
@Slf4j
public class SecurityMonitor {

    /**
     * JNA interface for ptrace syscall.
     */
    private interface LibC extends Library {
        LibC INSTANCE = Platform.isLinux() ? Native.load("c", LibC.class) : null;
        
        /**
         * ptrace - process trace
         * 
         * @param request PTRACE_TRACEME = 0
         * @param pid target pid (0 for TRACEME)
         * @param addr address (NULL for TRACEME)
         * @param data data (NULL for TRACEME)
         * @return 0 on success, -1 on error
         */
        long ptrace(int request, int pid, long addr, long data);
    }

    // ptrace constants
    private static final int PTRACE_TRACEME = 0;
    
    // Check intervals (randomized within range to avoid predictable timing)
    private static final long MIN_CHECK_INTERVAL_MS = 5000;   // 5 seconds
    private static final long MAX_CHECK_INTERVAL_MS = 15000;  // 15 seconds
    
    // Timing anomaly detection
    private static final long TIMING_CHECK_THRESHOLD_NS = 50_000_000; // 50ms - way too slow for simple ops
    
    // TracerPid pattern in /proc/self/status
    private static final Pattern TRACER_PID_PATTERN = Pattern.compile("TracerPid:\\s*(\\d+)");
    private static final Path PROC_STATUS = Path.of("/proc/self/status");
    
    private final Runnable onDetection;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean detected = new AtomicBoolean(false);
    
    private ScheduledFuture<?> checkTask;
    private long lastCheckTimeNs = 0;
    
    /**
     * Create a security monitor.
     * 
     * @param onDetection callback to invoke when debugging is detected (typically bot shutdown)
     */
    public SecurityMonitor(Runnable onDetection) {
        this.onDetection = onDetection;
        // Thread name obfuscated to look like standard JVM thread
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Thread-2");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start periodic security checks.
     */
    public void start() {
        if (!Platform.isLinux()) {
            log.debug("Security monitoring disabled on non-Linux platform");
            return;
        }
        
        if (running.compareAndSet(false, true)) {
            log.debug("Security monitoring started");
            scheduleNextCheck();
        }
    }
    
    /**
     * Stop security monitoring.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (checkTask != null) {
                checkTask.cancel(false);
            }
            executor.shutdown();
            log.debug("Security monitoring stopped");
        }
    }
    
    /**
     * Check if debugging was detected.
     */
    public boolean isDetected() {
        return detected.get();
    }
    
    /**
     * Schedule the next security check with randomized delay.
     */
    private void scheduleNextCheck() {
        if (!running.get()) return;
        
        // Randomize interval to avoid predictable timing
        long delay = MIN_CHECK_INTERVAL_MS + 
                (long) (Math.random() * (MAX_CHECK_INTERVAL_MS - MIN_CHECK_INTERVAL_MS));
        
        checkTask = executor.schedule(this::performChecks, delay, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Perform all security checks.
     */
    private void performChecks() {
        if (!running.get()) return;
        
        try {
            boolean tracerDetected = checkTracerPid();
            boolean ptraceDetected = checkPtrace();
            boolean timingAnomaly = checkTimingAnomaly();
            
            if (tracerDetected || ptraceDetected || timingAnomaly) {
                handleDetection(tracerDetected, ptraceDetected, timingAnomaly);
                return;
            }
            
        } catch (Exception e) {
            // Log but don't crash - security checks are defensive
            log.debug("Security check error: {}", e.getMessage());
        }
        
        // Schedule next check
        scheduleNextCheck();
    }
    
    /**
     * Check /proc/self/status for non-zero TracerPid.
     * TracerPid is the PID of the process tracing this process (0 if none).
     */
    private boolean checkTracerPid() {
        try {
            String status = Files.readString(PROC_STATUS);
            Matcher matcher = TRACER_PID_PATTERN.matcher(status);
            
            if (matcher.find()) {
                int tracerPid = Integer.parseInt(matcher.group(1));
                if (tracerPid != 0) {
                    log.warn("TracerPid detected: {}", tracerPid);
                    return true;
                }
            }
        } catch (IOException e) {
            // Can't read proc - might be containerized or restricted
            log.debug("Cannot read /proc/self/status: {}", e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Attempt PTRACE_TRACEME to detect if already being traced.
     * If a debugger is attached, this call will fail with EPERM.
     * 
     * Note: This is a one-shot check - once we successfully call TRACEME,
     * subsequent calls will also fail. So we only do this on first check.
     */
    private boolean checkPtrace() {
        if (LibC.INSTANCE == null) {
            return false;
        }
        
        try {
            // ptrace(PTRACE_TRACEME, 0, NULL, NULL)
            // Returns 0 on success, -1 on error
            // If already being traced, errno will be EPERM (1)
            long result = LibC.INSTANCE.ptrace(PTRACE_TRACEME, 0, 0, 0);
            
            if (result == -1) {
                int errno = Native.getLastError();
                // EPERM (1) = already being traced
                // EPERM can also mean we already called TRACEME ourselves
                if (errno == 1) {
                    // Check if this is first call (we haven't traced ourselves yet)
                    // On subsequent calls, we expect EPERM because we called TRACEME
                    if (lastCheckTimeNs == 0) {
                        log.warn("ptrace(TRACEME) failed with EPERM - possible debugger");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("ptrace check error: {}", e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Check for timing anomalies that might indicate syscall interception.
     * strace/perf/ltrace add significant overhead to system calls.
     * 
     * We measure time for a simple operation that should be fast.
     */
    private boolean checkTimingAnomaly() {
        long startNs = System.nanoTime();
        
        // Perform operations that should be fast but would be slow under tracing
        // Multiple small file reads - heavily affected by strace
        try {
            for (int i = 0; i < 10; i++) {
                Files.exists(PROC_STATUS);
            }
        } catch (Exception e) {
            // Ignore
        }
        
        long elapsed = System.nanoTime() - startNs;
        lastCheckTimeNs = elapsed;
        
        // 10 simple exists() calls should take < 5ms normally
        // Under strace, each syscall adds ~1-5ms overhead
        if (elapsed > TIMING_CHECK_THRESHOLD_NS) {
            log.warn("Timing anomaly detected: {}ms for simple operations", elapsed / 1_000_000);
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle detection - log and trigger callback.
     */
    private void handleDetection(boolean tracer, boolean ptrace, boolean timing) {
        if (detected.compareAndSet(false, true)) {
            running.set(false);
            
            StringBuilder reason = new StringBuilder("Security check failed: ");
            if (tracer) reason.append("TracerPid ");
            if (ptrace) reason.append("ptrace ");
            if (timing) reason.append("timing ");
            
            log.error(reason.toString().trim());
            
            // Trigger shutdown callback
            if (onDetection != null) {
                try {
                    onDetection.run();
                } catch (Exception e) {
                    log.error("Detection callback failed: {}", e.getMessage());
                }
            }
        }
    }
}
