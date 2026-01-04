package com.rocinante.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitors for debugging/tracing attempts and JVM tampering that could indicate analysis.
 * 
 * <h2>Detection Methods</h2>
 * <ol>
 *   <li><b>TracerPid check:</b> Reads /proc/self/status for non-zero TracerPid</li>
 *   <li><b>ptrace(PTRACE_TRACEME):</b> Fails if already being traced</li>
 *   <li><b>Timing anomaly detection:</b> Syscall interception causes slowdowns</li>
 *   <li><b>JVM argument integrity:</b> Detects injected agents, debuggers, profilers</li>
 * </ol>
 * 
 * <h2>JVM Argument Checks</h2>
 * <p>Verifies:
 * <ul>
 *   <li>Expected arguments are present (--add-opens for reflection access)</li>
 *   <li>No suspicious arguments were injected:
 *     <ul>
 *       <li>-javaagent: (bytecode modification agents)</li>
 *       <li>-agentlib: / -agentpath: (native agents like debuggers)</li>
 *       <li>-Xrunjdwp: / jdwp (remote debugging)</li>
 *       <li>Profiling arguments (async-profiler, YourKit, etc.)</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <p>When detection occurs, triggers graceful shutdown via callback.
 * 
 * <p><b>Note:</b> This is defensive, not aggressive. We simply exit if being analyzed,
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
    
    // ========================================================================
    // LD_PRELOAD Spoofing Verification
    // ========================================================================
    // 
    // Our LD_PRELOAD library spoofs certain system files to hide container/VM
    // indicators. If these spoofed values are not present, the library may have
    // been unloaded, bypassed, or never loaded - indicating potential analysis.
    // ========================================================================
    
    /**
     * Path to /proc/cpuinfo for CPU model verification.
     */
    private static final Path PROC_CPUINFO = Path.of("/proc/cpuinfo");
    
    /**
     * Path to /proc/self/cgroup for container detection.
     */
    private static final Path PROC_CGROUP = Path.of("/proc/self/cgroup");
    
    /**
     * Expected CPU model string from our LD_PRELOAD spoofing library.
     * If this is not present, spoofing is not active.
     */
    private static final String EXPECTED_SPOOFED_CPU = "AMD Custom APU 0405";
    
    /**
     * Container indicators that should NOT be visible when spoofing is active.
     * Presence of these indicates LD_PRELOAD bypass or container escape detection.
     */
    private static final Set<String> CONTAINER_INDICATORS = Set.of(
            "docker",
            "kubepods",
            "containerd",
            "lxc",
            "podman",
            "crio"
    );
    
    /**
     * Whether LD_PRELOAD verification is enabled.
     * Can be disabled for development/testing without the spoofing library.
     */
    private volatile boolean ldPreloadVerificationEnabled = true;
    
    // ========================================================================
    // JVM Argument Integrity Configuration
    // ========================================================================
    
    /**
     * Expected JVM arguments that should be present.
     * Missing these suggests the JVM was started incorrectly or tampered with.
     */
    private static final Set<String> EXPECTED_ARG_PATTERNS = Set.of(
            "--add-opens=java.desktop"  // Required for Robot/AWT reflection access
    );
    
    /**
     * Suspicious argument patterns that indicate debugging/profiling tools.
     * These are checked via substring matching (case-insensitive for some).
     */
    private static final Set<String> SUSPICIOUS_ARG_PATTERNS = Set.of(
            "-javaagent:",      // Java agent injection (bytecode modification)
            "-agentlib:",       // Native agent (debuggers, profilers)
            "-agentpath:",      // Native agent by path
            "-Xrunjdwp",        // Legacy remote debugging
            "jdwp",             // JDWP (Java Debug Wire Protocol)
            "-Xdebug",          // Debug mode
            "-Xnoagent",        // Sometimes paired with debugging
            "suspend=",         // Debugger suspend (often with jdwp)
            "transport=dt_",    // Debug transport
            "async-profiler",   // async-profiler agent
            "libasyncProfiler", // async-profiler native lib
            "YourKit",          // YourKit profiler
            "JProfiler",        // JProfiler
            "VisualVM",         // VisualVM profiler
            "-XX:+HeapDumpOnOutOfMemoryError",  // Can be used to extract heap state
            "-XX:HeapDumpPath"  // Heap dump path specification
    );
    
    /**
     * Known-safe argument patterns that might match suspicious patterns but are OK.
     * These take precedence over suspicious patterns.
     */
    private static final Set<String> SAFE_ARG_PATTERNS = Set.of(
            // RuneLite's own required arguments
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED"
    );
    
    /**
     * Cached JVM arguments from startup.
     * We capture these once at start to detect if they change (which shouldn't happen).
     */
    private List<String> initialJvmArgs;
    
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
        // Capture initial JVM arguments for integrity checking
        // This is platform-independent, so do it before the Linux check
        captureInitialJvmArgs();
        
        // Perform initial JVM integrity check immediately (platform-independent)
        JvmIntegrityResult initialCheck = checkJvmIntegrity();
        if (!initialCheck.isClean()) {
            log.error("[SECURITY] JVM integrity check failed on startup: {}", initialCheck.reason());
            handleDetection(false, false, false, true, initialCheck.reason());
            return;
        }
        
        if (!Platform.isLinux()) {
            log.debug("Linux-specific security monitoring disabled on non-Linux platform");
            // Still run JVM integrity checks periodically
        }
        
        if (running.compareAndSet(false, true)) {
            log.debug("Security monitoring started");
            scheduleNextCheck();
        }
    }
    
    /**
     * Capture the JVM arguments at startup for later comparison.
     */
    private void captureInitialJvmArgs() {
        try {
            initialJvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            log.debug("Captured {} initial JVM arguments", initialJvmArgs.size());
        } catch (Exception e) {
            log.warn("Failed to capture JVM arguments: {}", e.getMessage());
            initialJvmArgs = List.of();
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
            // Linux-specific checks
            boolean tracerDetected = false;
            boolean ptraceDetected = false;
            boolean timingAnomaly = false;
            LdPreloadResult ldPreloadResult = LdPreloadResult.clean();
            
            if (Platform.isLinux()) {
                tracerDetected = checkTracerPid();
                ptraceDetected = checkPtrace();
                timingAnomaly = checkTimingAnomaly();
                ldPreloadResult = checkLdPreloadActive();
            }
            
            // Platform-independent JVM integrity check
            JvmIntegrityResult jvmResult = checkJvmIntegrity();
            
            if (tracerDetected || ptraceDetected || timingAnomaly || 
                    !jvmResult.isClean() || !ldPreloadResult.isActive()) {
                handleDetection(tracerDetected, ptraceDetected, timingAnomaly, 
                        !jvmResult.isClean(), jvmResult.reason(),
                        !ldPreloadResult.isActive(), ldPreloadResult.reason());
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
        handleDetection(tracer, ptrace, timing, false, null, false, null);
    }
    
    /**
     * Handle detection with JVM integrity details.
     */
    private void handleDetection(boolean tracer, boolean ptrace, boolean timing, 
                                  boolean jvmTampered, String jvmReason) {
        handleDetection(tracer, ptrace, timing, jvmTampered, jvmReason, false, null);
    }
    
    /**
     * Handle detection with JVM integrity and LD_PRELOAD details.
     */
    private void handleDetection(boolean tracer, boolean ptrace, boolean timing, 
                                  boolean jvmTampered, String jvmReason,
                                  boolean ldPreloadMissing, String ldReason) {
        if (detected.compareAndSet(false, true)) {
            running.set(false);
            
            StringBuilder reason = new StringBuilder("[SECURITY] Check failed: ");
            if (tracer) reason.append("TracerPid ");
            if (ptrace) reason.append("ptrace ");
            if (timing) reason.append("timing ");
            if (jvmTampered) {
                reason.append("JVM(").append(jvmReason != null ? jvmReason : "integrity").append(") ");
            }
            if (ldPreloadMissing) {
                reason.append("LD_PRELOAD(").append(ldReason != null ? ldReason : "missing").append(") ");
            }
            
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

    // ========================================================================
    // JVM Integrity Checks
    // ========================================================================

    /**
     * Result of JVM integrity check.
     */
    private record JvmIntegrityResult(boolean isClean, String reason) {
        static JvmIntegrityResult clean() {
            return new JvmIntegrityResult(true, null);
        }
        
        static JvmIntegrityResult tampered(String reason) {
            return new JvmIntegrityResult(false, reason);
        }
    }

    /**
     * Check JVM argument integrity.
     * 
     * <p>Verifies:
     * <ol>
     *   <li>Expected arguments are present (required for proper operation)</li>
     *   <li>No suspicious arguments were added (agents, debuggers, profilers)</li>
     *   <li>Arguments haven't changed since startup (shouldn't be possible, but check anyway)</li>
     * </ol>
     * 
     * @return JvmIntegrityResult indicating clean or tampered with reason
     */
    private JvmIntegrityResult checkJvmIntegrity() {
        try {
            List<String> currentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            
            // Check 1: Arguments haven't changed since startup
            if (initialJvmArgs != null && !initialJvmArgs.equals(currentArgs)) {
                // This shouldn't happen - JVM args don't change at runtime
                // If they appear different, something is very wrong
                return JvmIntegrityResult.tampered("args_modified_at_runtime");
            }
            
            // Check 2: Expected arguments are present
            for (String expected : EXPECTED_ARG_PATTERNS) {
                boolean found = currentArgs.stream()
                        .anyMatch(arg -> arg.contains(expected));
                if (!found) {
                    // Missing expected argument - JVM may have been started wrong
                    // or arguments were stripped somehow
                    log.debug("Missing expected JVM arg pattern: {}", expected);
                    // Don't fail on this - might be development environment
                    // Just log for monitoring
                }
            }
            
            // Check 3: No suspicious arguments
            for (String arg : currentArgs) {
                String argLower = arg.toLowerCase();
                
                // Skip if it matches a known-safe pattern
                boolean isSafe = SAFE_ARG_PATTERNS.stream()
                        .anyMatch(safe -> arg.contains(safe));
                if (isSafe) {
                    continue;
                }
                
                // Check against suspicious patterns
                for (String suspicious : SUSPICIOUS_ARG_PATTERNS) {
                    String suspiciousLower = suspicious.toLowerCase();
                    if (argLower.contains(suspiciousLower)) {
                        return JvmIntegrityResult.tampered("suspicious_arg:" + suspicious);
                    }
                }
            }
            
            return JvmIntegrityResult.clean();
            
        } catch (Exception e) {
            log.debug("JVM integrity check error: {}", e.getMessage());
            // Don't fail on error - might be security manager restriction
            return JvmIntegrityResult.clean();
        }
    }

    // ========================================================================
    // LD_PRELOAD Spoofing Verification
    // ========================================================================

    /**
     * Result of LD_PRELOAD verification.
     */
    private record LdPreloadResult(boolean isActive, String reason) {
        static LdPreloadResult clean() {
            return new LdPreloadResult(true, null);
        }
        
        static LdPreloadResult inactive(String reason) {
            return new LdPreloadResult(false, reason);
        }
    }

    /**
     * Verify that the LD_PRELOAD spoofing library is active.
     * 
     * <p>Our spoofing library intercepts reads to /proc files to hide container
     * and virtualization indicators. If the expected spoofed values are not
     * present, the library may have been:
     * <ul>
     *   <li>Never loaded (missing LD_PRELOAD environment variable)</li>
     *   <li>Unloaded (dlclose or library unmap)</li>
     *   <li>Bypassed (direct syscalls, seccomp filter)</li>
     *   <li>Overridden (another LD_PRELOAD taking precedence)</li>
     * </ul>
     * 
     * <p>Any of these indicates potential analysis or tampering.
     * 
     * @return LdPreloadResult indicating whether spoofing is active
     */
    private LdPreloadResult checkLdPreloadActive() {
        if (!ldPreloadVerificationEnabled) {
            return LdPreloadResult.clean();
        }
        
        try {
            // Check 1: Verify CPU info is spoofed
            if (Files.exists(PROC_CPUINFO)) {
                String cpuinfo = Files.readString(PROC_CPUINFO);
                
                if (!cpuinfo.contains(EXPECTED_SPOOFED_CPU)) {
                    log.warn("CPU spoofing not active - expected '{}' not found", EXPECTED_SPOOFED_CPU);
                    return LdPreloadResult.inactive("cpu_spoof_missing");
                }
            }
            
            // Check 2: Verify container indicators are hidden
            if (Files.exists(PROC_CGROUP)) {
                String cgroup = Files.readString(PROC_CGROUP);
                String cgroupLower = cgroup.toLowerCase();
                
                for (String indicator : CONTAINER_INDICATORS) {
                    if (cgroupLower.contains(indicator)) {
                        log.warn("Container indicator '{}' visible in cgroup - LD_PRELOAD bypass detected", 
                                indicator);
                        return LdPreloadResult.inactive("container_visible:" + indicator);
                    }
                }
            }
            
            // Check 3: Verify /proc/self/exe doesn't reveal unexpected runtime
            // (Some analysis tools replace the executable or use wine/qemu)
            Path procExe = Path.of("/proc/self/exe");
            if (Files.exists(procExe)) {
                try {
                    Path realPath = Files.readSymbolicLink(procExe);
                    String realPathStr = realPath.toString().toLowerCase();
                    
                    // Check for emulators/analysis tools
                    if (realPathStr.contains("wine") || 
                        realPathStr.contains("qemu") ||
                        realPathStr.contains("valgrind") ||
                        realPathStr.contains("gdb")) {
                        log.warn("Suspicious runtime detected: {}", realPath);
                        return LdPreloadResult.inactive("suspicious_runtime:" + realPath.getFileName());
                    }
                } catch (IOException e) {
                    // Can't read symlink - might be restricted, not necessarily bad
                    log.debug("Cannot read /proc/self/exe: {}", e.getMessage());
                }
            }
            
            return LdPreloadResult.clean();
            
        } catch (IOException e) {
            log.debug("LD_PRELOAD verification error: {}", e.getMessage());
            // Can't verify - assume OK (might be in restricted environment)
            return LdPreloadResult.clean();
        }
    }

    /**
     * Enable or disable LD_PRELOAD verification.
     * 
     * <p>Useful for development/testing without the spoofing library.
     * 
     * @param enabled true to enable verification (default), false to skip
     */
    public void setLdPreloadVerificationEnabled(boolean enabled) {
        this.ldPreloadVerificationEnabled = enabled;
        log.debug("LD_PRELOAD verification {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Check if LD_PRELOAD verification is enabled.
     * 
     * @return true if verification is enabled
     */
    public boolean isLdPreloadVerificationEnabled() {
        return ldPreloadVerificationEnabled;
    }
}
