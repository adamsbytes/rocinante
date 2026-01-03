package com.rocinante.input.uinput;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * JNA interface for Linux libc functions needed for uinput device setup
 * and high-precision timing.
 * 
 * Provides:
 * - File operations (open, close, read, write)
 * - ioctl for device control
 * - clock_gettime for nanosecond-precision timestamps
 * - timerfd for hardware-precision polling
 */
public interface LibCExt extends Library {

    /**
     * Singleton instance loaded from libc.
     */
    LibCExt INSTANCE = Native.load("c", LibCExt.class);

    // ========================================================================
    // Clock constants
    // ========================================================================

    /** Monotonic clock - not affected by NTP or manual time changes */
    int CLOCK_MONOTONIC = 1;

    // ========================================================================
    // timerfd constants
    // ========================================================================

    /** timerfd flag: non-blocking I/O */
    int TFD_NONBLOCK = 04000;
    
    /** timerfd flag: close-on-exec */
    int TFD_CLOEXEC = 02000000;
    
    /** timerfd_settime flag: use absolute time (vs relative) */
    int TFD_TIMER_ABSTIME = 1;

    // ========================================================================
    // Structures
    // ========================================================================

    /**
     * struct timespec - time with nanosecond precision.
     * Used by clock_gettime and timerfd.
     */
    @Structure.FieldOrder({"tv_sec", "tv_nsec"})
    class Timespec extends Structure {
        public long tv_sec;   // seconds
        public long tv_nsec;  // nanoseconds (0-999999999)

        public Timespec() {}

        public Timespec(long sec, long nsec) {
            this.tv_sec = sec;
            this.tv_nsec = nsec;
        }
        
        /**
         * Set from nanoseconds since epoch.
         */
        public void setNanos(long totalNanos) {
            this.tv_sec = totalNanos / 1_000_000_000L;
            this.tv_nsec = totalNanos % 1_000_000_000L;
        }
        
        /**
         * Get total nanoseconds.
         */
        public long toNanos() {
            return tv_sec * 1_000_000_000L + tv_nsec;
        }
    }

    /**
     * struct itimerspec - timer specification for timerfd.
     * Contains initial expiration time and interval for repeating timers.
     */
    @Structure.FieldOrder({"it_interval", "it_value"})
    class ITimerspec extends Structure {
        public Timespec it_interval;  // interval for periodic timer
        public Timespec it_value;     // initial expiration

        public ITimerspec() {
            this.it_interval = new Timespec();
            this.it_value = new Timespec();
        }
    }

    // ========================================================================
    // File operations
    // ========================================================================

    /**
     * Open a file descriptor.
     * 
     * @param path the file path (e.g., "/dev/uinput")
     * @param flags open flags (O_WRONLY, O_NONBLOCK, etc.)
     * @return file descriptor on success, -1 on error
     */
    int open(String path, int flags);

    /**
     * Close a file descriptor.
     * 
     * @param fd the file descriptor to close
     * @return 0 on success, -1 on error
     */
    int close(int fd);

    /**
     * Read from a file descriptor.
     * 
     * @param fd the file descriptor
     * @param buf pointer to buffer to read into
     * @param count maximum bytes to read
     * @return number of bytes read, -1 on error
     */
    long read(int fd, Pointer buf, long count);

    /**
     * Write bytes to a file descriptor.
     * 
     * @param fd the file descriptor
     * @param buf pointer to the buffer
     * @param count number of bytes to write
     * @return number of bytes written, -1 on error
     */
    int write(int fd, Pointer buf, int count);

    // ========================================================================
    // ioctl operations
    // ========================================================================

    /**
     * Perform an ioctl operation with an integer argument.
     * Used for UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT, etc.
     */
    int ioctl(int fd, int request, int arg);

    /**
     * Perform an ioctl operation with a pointer argument.
     * Used for UI_DEV_SETUP with uinput_setup structure.
     */
    int ioctl(int fd, int request, Pointer arg);

    /**
     * Perform an ioctl operation with no argument.
     * Used for UI_DEV_CREATE and UI_DEV_DESTROY.
     */
    int ioctl(int fd, int request);

    // ========================================================================
    // High-precision timing
    // ========================================================================

    /**
     * Get current time from a clock with nanosecond precision.
     * 
     * @param clockId clock to query (CLOCK_MONOTONIC recommended)
     * @param tp pointer to timespec structure to fill
     * @return 0 on success, -1 on error
     */
    int clock_gettime(int clockId, Timespec tp);

    /**
     * Create a timerfd file descriptor for high-precision timing.
     * 
     * @param clockId clock to use (CLOCK_MONOTONIC recommended)
     * @param flags TFD_NONBLOCK, TFD_CLOEXEC, or 0
     * @return file descriptor on success, -1 on error
     */
    int timerfd_create(int clockId, int flags);

    /**
     * Arm or disarm a timerfd timer.
     * 
     * @param fd timerfd file descriptor
     * @param flags 0 for relative time, TFD_TIMER_ABSTIME for absolute time
     * @param new_value new timer settings
     * @param old_value if non-null, receives old timer settings
     * @return 0 on success, -1 on error
     */
    int timerfd_settime(int fd, int flags, ITimerspec new_value, ITimerspec old_value);

    // ========================================================================
    // Error handling
    // ========================================================================

    /**
     * Get error string for an errno value.
     * 
     * @param errno the error number
     * @return the error string
     */
    String strerror(int errno);
}
