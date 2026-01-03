package com.rocinante.input.uinput;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * JNA interface for Linux libc functions needed for uinput device setup.
 * 
 * Only used for one-time setup operations (open, ioctl, close).
 * Event writes use pure Java FileChannel for performance.
 */
public interface LibCExt extends Library {

    /**
     * Singleton instance loaded from libc.
     */
    LibCExt INSTANCE = Native.load("c", LibCExt.class);

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
     * Perform an ioctl operation with an integer argument.
     * Used for UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT, etc.
     * 
     * @param fd the file descriptor
     * @param request the ioctl request code
     * @param arg the integer argument
     * @return 0 on success, -1 on error
     */
    int ioctl(int fd, int request, int arg);

    /**
     * Perform an ioctl operation with a pointer argument.
     * Used for UI_DEV_SETUP with uinput_setup structure.
     * 
     * @param fd the file descriptor
     * @param request the ioctl request code
     * @param arg pointer to the structure
     * @return 0 on success, -1 on error
     */
    int ioctl(int fd, int request, Pointer arg);

    /**
     * Perform an ioctl operation with no argument.
     * Used for UI_DEV_CREATE and UI_DEV_DESTROY.
     * 
     * @param fd the file descriptor
     * @param request the ioctl request code
     * @return 0 on success, -1 on error
     */
    int ioctl(int fd, int request);

    /**
     * Write bytes to a file descriptor.
     * 
     * @param fd the file descriptor
     * @param buf pointer to the buffer
     * @param count number of bytes to write
     * @return number of bytes written, -1 on error
     */
    int write(int fd, Pointer buf, int count);

    /**
     * Get the last error number.
     * Note: JNA doesn't directly support errno, but Native.getLastError() does.
     * 
     * @return the error string for the given errno
     */
    String strerror(int errno);
}
