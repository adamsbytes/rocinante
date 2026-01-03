package com.rocinante.input.uinput;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * Base class for uinput virtual device management.
 * 
 * Handles:
 * - Device creation via JNA ioctl calls (one-time setup)
 * - Event emission via JNA write() to the device fd
 * - Polling rate emulation via scheduled event emission
 * - Physical path setting for realistic hardware fingerprint
 * - Buffer reuse to avoid GC pressure
 * - Proper cleanup on close
 * 
 * This is the kernel-level input injection mechanism that bypasses
 * java.awt.Robot's XTest extension, making input indistinguishable
 * from physical hardware.
 */
@Slf4j
public abstract class UInputDevice implements Closeable {

    private static final String UINPUT_PATH = "/dev/uinput";
    private static final int INPUT_EVENT_SIZE = 24; // sizeof(struct input_event) on 64-bit
    private static final short DEFAULT_VERSION = 0x0111;

    /**
     * The file descriptor for the uinput device.
     * Used for both ioctl configuration AND event writes.
     */
    protected final int fd;

    /**
     * Reusable native memory buffer for event emission (24 bytes per event).
     * Allocated once, reused for every event to avoid GC.
     * Uses JNA Memory for direct native access.
     */
    protected final Memory eventBuffer;

    /**
     * Device preset configuration.
     */
    @Getter
    protected final DevicePreset preset;

    /**
     * Whether the device has been successfully created.
     */
    @Getter
    protected volatile boolean created = false;

    /**
     * The physical USB path set for this device (for testing/debugging).
     */
    @Getter
    protected String physPath;

    // ========================================================================
    // Polling Rate Emulation
    // ========================================================================

    /**
     * Active polling rate in Hz.
     */
    @Getter
    protected final int pollingRate;

    /**
     * Interval between event emissions in nanoseconds.
     */
    protected final long pollIntervalNanos;

    /**
     * Scheduled executor for polling rate emulation.
     */
    private final ScheduledExecutorService pollExecutor;

    /**
     * Queue of pending events to emit at the next poll interval.
     */
    private final BlockingQueue<PendingEvent> eventQueue;

    /**
     * Whether the polling emulation thread is running.
     */
    private final AtomicBoolean pollingActive;

    /**
     * Future for the scheduled polling task.
     */
    private ScheduledFuture<?> pollFuture;

    /**
     * Last time we emitted events (for timing accuracy).
     */
    private volatile long lastEmitTimeNanos;

    /**
     * A pending input event waiting to be emitted.
     */
    private static class PendingEvent {
        final short type;
        final short code;
        final int value;
        
        PendingEvent(short type, short code, int value) {
            this.type = type;
            this.code = code;
            this.value = value;
        }
    }

    /**
     * Create a new uinput device.
     * 
     * @param preset the device preset defining identity (name, vendor, product)
     * @param pollingRate the polling rate in Hz (e.g., 125, 250, 500, 1000)
     * @param profileId unique profile identifier for deterministic physical path generation
     * @throws IllegalStateException if /dev/uinput cannot be opened or device creation fails
     */
    protected UInputDevice(DevicePreset preset, int pollingRate, String profileId) {
        this.preset = preset;
        this.pollingRate = pollingRate;
        this.pollIntervalNanos = TimeUnit.SECONDS.toNanos(1) / pollingRate;
        
        // Initialize polling infrastructure
        this.eventQueue = new LinkedBlockingQueue<>();
        this.pollingActive = new AtomicBoolean(false);
        
        // Create a dedicated thread for polling with high priority
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "uinput-poll-" + preset.getDeviceType().name().toLowerCase());
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.pollExecutor = executor;
        
        // Open /dev/uinput via JNA
        this.fd = LibCExt.INSTANCE.open(UINPUT_PATH, O_WRONLY | O_NONBLOCK);
        if (fd < 0) {
            int errno = Native.getLastError();
            pollExecutor.shutdownNow();
            throw new IllegalStateException(
                "Cannot open " + UINPUT_PATH + ": errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + "). " +
                "Ensure /dev/uinput exists and is accessible (docker --device=/dev/uinput)");
        }

        try {
            // Configure device capabilities (implemented by subclasses)
            configureCapabilities();

            // Setup device identity
            setupDeviceIdentity();

            // Set physical path to look like real USB hardware
            this.physPath = PhysPathGenerator.generate(profileId, preset.getDeviceType());
            setPhys(physPath);

            // Create the virtual device
            int result = LibCExt.INSTANCE.ioctl(fd, UI_DEV_CREATE);
            if (result < 0) {
                int errno = Native.getLastError();
                throw new IllegalStateException(
                    "UI_DEV_CREATE failed: errno=" + errno + 
                    " (" + LibCExt.INSTANCE.strerror(errno) + ")");
            }

            // Allocate reusable native memory buffer for events
            this.eventBuffer = new Memory(INPUT_EVENT_SIZE);

            this.created = true;
            
            // Start polling emulation
            startPolling();

            log.info("Created uinput device: {} (vendor={}, product={}, polling={}Hz, phys={})",
                preset.getName(), 
                String.format("0x%04x", preset.getVendorId()),
                String.format("0x%04x", preset.getProductId()),
                pollingRate,
                physPath);

        } catch (Exception e) {
            // Cleanup on failure
            pollExecutor.shutdownNow();
            LibCExt.INSTANCE.close(fd);
            throw new IllegalStateException("Failed to create uinput device: " + e.getMessage(), e);
        }
    }

    /**
     * Configure device capabilities via ioctl.
     * Implemented by subclasses to set appropriate EV_*, KEY_*, REL_* bits.
     */
    protected abstract void configureCapabilities();

    /**
     * Setup device identity via UI_DEV_SETUP ioctl.
     */
    private void setupDeviceIdentity() {
        UInputSetup setup = new UInputSetup();
        setup.configure(
            BUS_USB,
            preset.getVendorId(),
            preset.getProductId(),
            DEFAULT_VERSION,
            preset.getName()
        );

        int result = LibCExt.INSTANCE.ioctl(fd, UI_DEV_SETUP, setup.getPointer());
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException(
                "UI_DEV_SETUP failed: errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    /**
     * Set the physical path for this device.
     * This appears in /proc/bus/input/devices under "Phys:" and helps
     * make the virtual device look like real USB hardware.
     * 
     * @param physPath the physical path (e.g., "usb-0000:00:14.0-2/input0")
     * @throws IllegalStateException if the ioctl fails
     */
    protected void setPhys(String physPath) {
        byte[] pathBytes = physPath.getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length >= UINPUT_MAX_NAME_SIZE) {
            throw new IllegalArgumentException("Physical path too long (max " + (UINPUT_MAX_NAME_SIZE - 1) + " bytes)");
        }
        
        Memory physMem = new Memory(UINPUT_MAX_NAME_SIZE);
        physMem.clear();
        physMem.write(0, pathBytes, 0, pathBytes.length);
        // Already zero-terminated by clear()

        int result = LibCExt.INSTANCE.ioctl(fd, UI_SET_PHYS, physMem);
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException(
                "UI_SET_PHYS failed for path '" + physPath + "': errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    // ========================================================================
    // Polling Rate Emulation
    // ========================================================================

    /**
     * Start the polling emulation thread.
     */
    private void startPolling() {
        if (pollingActive.compareAndSet(false, true)) {
            lastEmitTimeNanos = System.nanoTime();
            
            // Schedule polling at fixed rate
            long intervalMicros = TimeUnit.NANOSECONDS.toMicros(pollIntervalNanos);
            pollFuture = pollExecutor.scheduleAtFixedRate(
                this::pollEmit,
                intervalMicros,
                intervalMicros,
                TimeUnit.MICROSECONDS
            );
        }
    }

    /**
     * Poll and emit queued events.
     * Called at the polling rate interval.
     */
    private void pollEmit() {
        if (!created || !pollingActive.get()) {
            return;
        }

        // Drain all queued events
        PendingEvent event;
        boolean hasEvents = false;
        
        while ((event = eventQueue.poll()) != null) {
            emitEventImmediate(event.type, event.code, event.value);
            hasEvents = true;
        }

        // Only emit sync if we had events
        if (hasEvents) {
            emitEventImmediate(EV_SYN, SYN_REPORT, 0);
        }

        lastEmitTimeNanos = System.nanoTime();
    }

    /**
     * Queue an event for emission at the next poll interval.
     * This method returns immediately - the event will be emitted
     * by the polling thread at the device's polling rate.
     * 
     * @param type event type (EV_REL, EV_KEY, etc.)
     * @param code event code (REL_X, BTN_LEFT, KEY_A, etc.)
     * @param value event value (movement delta, 0/1 for buttons, etc.)
     */
    protected void queueEvent(short type, short code, int value) {
        if (!created) {
            throw new IllegalStateException("Device not created");
        }
        eventQueue.offer(new PendingEvent(type, code, value));
    }

    /**
     * Queue an event and wait for the next poll cycle to complete.
     * Use this when you need to ensure the event has been sent before continuing.
     * 
     * @param type event type
     * @param code event code
     * @param value event value
     */
    protected void queueEventAndWait(short type, short code, int value) {
        queueEvent(type, code, value);
        waitForNextPoll();
    }

    /**
     * Wait for the next poll cycle.
     * Returns after the polling thread has processed queued events.
     */
    protected void waitForNextPoll() {
        long waitNanos = pollIntervalNanos + (pollIntervalNanos / 2); // 1.5x interval for safety
        try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Emit an event immediately (bypassing the queue).
     * Use for synchronous operations or from the polling thread.
     * 
     * THREAD SAFETY: This method is synchronized because the eventBuffer
     * is shared. Multiple threads could otherwise corrupt the buffer.
     * 
     * @param type event type
     * @param code event code
     * @param value event value
     */
    protected synchronized void emitEventImmediate(short type, short code, int value) {
        if (!created) {
            throw new IllegalStateException("Device not created");
        }

        // Get current time
        long timeMs = System.currentTimeMillis();
        long timeSec = timeMs / 1000;
        long timeUsec = (timeMs % 1000) * 1000;

        // Write input_event struct to native memory
        eventBuffer.setLong(0, timeSec);
        eventBuffer.setLong(8, timeUsec);
        eventBuffer.setShort(16, type);
        eventBuffer.setShort(18, code);
        eventBuffer.setInt(20, value);

        // Write to the uinput fd
        int written = LibCExt.INSTANCE.write(fd, eventBuffer, INPUT_EVENT_SIZE);
        if (written != INPUT_EVENT_SIZE) {
            int errno = Native.getLastError();
            throw new RuntimeException("Failed to emit event: wrote " + written + 
                " bytes, expected " + INPUT_EVENT_SIZE + ", errno=" + errno +
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    /**
     * Emit a single input event.
     * Queues the event for emission at the next poll interval.
     * 
     * @param type event type (EV_REL, EV_KEY, etc.)
     * @param code event code (REL_X, BTN_LEFT, KEY_A, etc.)
     * @param value event value (movement delta, 0/1 for buttons, etc.)
     */
    protected void emitEvent(short type, short code, int value) {
        queueEvent(type, code, value);
    }

    /**
     * Emit a synchronization event to commit the current event frame.
     * When using polling, this is handled automatically by the polling thread.
     * This method is provided for backward compatibility but does nothing
     * as SYN_REPORT is emitted automatically after each poll batch.
     */
    protected void emitSync() {
        // SYN_REPORT is handled automatically by pollEmit()
        // This method is a no-op when using polling rate emulation
    }

    /**
     * Flush all queued events immediately.
     * Use sparingly - this bypasses the polling rate emulation.
     */
    protected void flushEvents() {
        PendingEvent event;
        boolean hasEvents = false;
        
        while ((event = eventQueue.poll()) != null) {
            emitEventImmediate(event.type, event.code, event.value);
            hasEvents = true;
        }
        
        if (hasEvents) {
            emitEventImmediate(EV_SYN, SYN_REPORT, 0);
        }
    }

    // ========================================================================
    // Capability Setters
    // ========================================================================

    /**
     * Set an event type capability bit.
     * @throws IllegalStateException if the ioctl fails
     */
    protected void setEvBit(int evType) {
        int result = LibCExt.INSTANCE.ioctl(fd, UI_SET_EVBIT, evType);
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException(
                "UI_SET_EVBIT(" + evType + ") failed: errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    /**
     * Set a key/button capability bit.
     * @throws IllegalStateException if the ioctl fails
     */
    protected void setKeyBit(int keyCode) {
        int result = LibCExt.INSTANCE.ioctl(fd, UI_SET_KEYBIT, keyCode);
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException(
                "UI_SET_KEYBIT(" + keyCode + ") failed: errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    /**
     * Set a relative axis capability bit.
     * @throws IllegalStateException if the ioctl fails
     */
    protected void setRelBit(int relCode) {
        int result = LibCExt.INSTANCE.ioctl(fd, UI_SET_RELBIT, relCode);
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException(
                "UI_SET_RELBIT(" + relCode + ") failed: errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    /**
     * Set a miscellaneous event capability bit (for MSC_SCAN).
     * @throws IllegalStateException if the ioctl fails
     */
    protected void setMscBit(int mscCode) {
        int result = LibCExt.INSTANCE.ioctl(fd, UI_SET_MSCBIT, mscCode);
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException(
                "UI_SET_MSCBIT(" + mscCode + ") failed: errno=" + errno + 
                " (" + LibCExt.INSTANCE.strerror(errno) + ")");
        }
    }

    /**
     * Close and destroy the virtual device.
     */
    @Override
    public void close() {
        // Stop polling
        pollingActive.set(false);
        if (pollFuture != null) {
            pollFuture.cancel(false);
        }
        pollExecutor.shutdownNow();
        
        // Flush any remaining events
        if (created) {
            flushEvents();
        }

        if (created) {
            // Destroy the virtual device
            int result = LibCExt.INSTANCE.ioctl(fd, UI_DEV_DESTROY);
            if (result < 0) {
                log.warn("UI_DEV_DESTROY failed: errno={}", Native.getLastError());
            }

            created = false;
        }

        // Close the file descriptor
        LibCExt.INSTANCE.close(fd);
        
        log.info("Closed uinput device: {}", preset.getName());
    }
}
