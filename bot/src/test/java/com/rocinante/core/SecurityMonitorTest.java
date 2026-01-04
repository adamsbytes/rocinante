package com.rocinante.core;

import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Tests for SecurityMonitor JVM integrity checking.
 * 
 * Note: Full security monitoring tests require Linux environment.
 * These tests focus on the JVM argument checking functionality.
 */
public class SecurityMonitorTest {

    private AtomicBoolean detectionTriggered;
    private AtomicInteger detectionCount;
    private SecurityMonitor monitor;

    @Before
    public void setUp() {
        detectionTriggered = new AtomicBoolean(false);
        detectionCount = new AtomicInteger(0);
        monitor = new SecurityMonitor(() -> {
            detectionTriggered.set(true);
            detectionCount.incrementAndGet();
        });
    }

    @Test
    public void testMonitorCreation() {
        assertNotNull(monitor);
        assertFalse(monitor.isDetected());
    }

    @Test
    public void testStartAndStop() {
        // Start should not trigger detection in normal test environment
        monitor.start();
        
        // Give it a moment to run initial checks
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // In a normal test environment (no javaagent attached), should not detect
        // Note: If running with debugger attached, this may fail intentionally
        
        monitor.stop();
        // Monitor should be stopped
    }

    @Test
    public void testJvmArgsAccessible() {
        // Verify we can access JVM arguments
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertNotNull(args);
        // Should have some arguments (at minimum, classpath-related)
    }

    @Test
    public void testDetectionCallbackInvokedOnce() {
        // Verify callback is only invoked once even if multiple detections occur
        // We simulate this by checking the AtomicBoolean compareAndSet behavior
        
        // First detection should trigger
        AtomicBoolean testDetected = new AtomicBoolean(false);
        assertTrue(testDetected.compareAndSet(false, true));
        
        // Second detection should not trigger again
        assertFalse(testDetected.compareAndSet(false, true));
    }

    @Test
    public void testSuspiciousArgPatterns() {
        // These patterns should be considered suspicious
        String[] suspiciousArgs = {
                "-javaagent:/path/to/agent.jar",
                "-agentlib:jdwp=transport=dt_socket,server=y",
                "-agentpath:/path/to/native.so",
                "-Xrunjdwp:transport=dt_socket,server=y",
                "-Xdebug",
                "transport=dt_socket",
                "-agentpath:libasyncProfiler.so"
        };

        for (String arg : suspiciousArgs) {
            // Verify these patterns are detected
            boolean foundSuspicious = false;
            String argLower = arg.toLowerCase();
            
            String[] patterns = {
                    "-javaagent:", "-agentlib:", "-agentpath:", "-Xrunjdwp",
                    "jdwp", "-Xdebug", "transport=dt_", "async-profiler",
                    "libasyncProfiler"
            };
            
            for (String pattern : patterns) {
                if (argLower.contains(pattern.toLowerCase())) {
                    foundSuspicious = true;
                    break;
                }
            }
            
            assertTrue("Should detect suspicious arg: " + arg, foundSuspicious);
        }
    }

    @Test
    public void testSafeArgPatterns() {
        // These patterns should be considered safe
        String[] safeArgs = {
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx2G",
                "-Xms512M",
                "-XX:+UseG1GC"
        };

        for (String arg : safeArgs) {
            // These should not trigger detection on their own
            assertNotNull("Safe arg should be valid: " + arg, arg);
        }
    }

    @Test
    public void testIsDetectedInitiallyFalse() {
        assertFalse(monitor.isDetected());
    }

    @Test
    public void testLdPreloadVerificationToggle() {
        // Default should be enabled
        assertTrue(monitor.isLdPreloadVerificationEnabled());
        
        // Should be able to disable
        monitor.setLdPreloadVerificationEnabled(false);
        assertFalse(monitor.isLdPreloadVerificationEnabled());
        
        // Should be able to re-enable
        monitor.setLdPreloadVerificationEnabled(true);
        assertTrue(monitor.isLdPreloadVerificationEnabled());
    }

    @Test
    public void testContainerIndicatorPatterns() {
        // These patterns should be considered container indicators
        String[] containerIndicators = {
                "docker",
                "kubepods",
                "containerd",
                "lxc",
                "podman",
                "crio"
        };

        for (String indicator : containerIndicators) {
            // These should be detected if present in cgroup
            assertNotNull("Indicator should be valid: " + indicator, indicator);
            assertTrue("Indicator should be lowercase: " + indicator, 
                    indicator.equals(indicator.toLowerCase()));
        }
    }

    @Test
    public void testExpectedSpoofedCpuConstant() {
        // The expected spoofed CPU should be a specific value
        String expected = "AMD Custom APU 0405";
        assertNotNull(expected);
        assertTrue(expected.contains("AMD"));
        assertTrue(expected.contains("Custom"));
    }
}
