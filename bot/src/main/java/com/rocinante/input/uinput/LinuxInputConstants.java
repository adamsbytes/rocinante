package com.rocinante.input.uinput;

/**
 * Linux input subsystem constants for uinput device creation and event emission.
 * 
 * These values come from:
 * - /usr/include/linux/input.h
 * - /usr/include/linux/input-event-codes.h
 * - /usr/include/linux/uinput.h
 */
public final class LinuxInputConstants {

    private LinuxInputConstants() {
        // Constants class
    }

    // ========================================================================
    // Event Types (EV_*)
    // ========================================================================

    /** Synchronization event */
    public static final short EV_SYN = 0x00;
    
    /** Key/button event */
    public static final short EV_KEY = 0x01;
    
    /** Relative axis event (mouse movement) */
    public static final short EV_REL = 0x02;
    
    /** Absolute axis event (touchscreen, tablet) */
    public static final short EV_ABS = 0x03;
    
    /** Miscellaneous event */
    public static final short EV_MSC = 0x04;

    // ========================================================================
    // Miscellaneous Codes (MSC_*)
    // ========================================================================

    /** Raw scancode (sent before KEY events by real hardware) */
    public static final short MSC_SCAN = 0x04;

    // ========================================================================
    // Synchronization Codes (SYN_*)
    // ========================================================================

    /** End of event frame - commit all pending events */
    public static final short SYN_REPORT = 0x00;

    // ========================================================================
    // Relative Axes (REL_*)
    // ========================================================================

    /** Relative X movement */
    public static final short REL_X = 0x00;
    
    /** Relative Y movement */
    public static final short REL_Y = 0x01;
    
    /** Relative Z (rarely used) */
    public static final short REL_Z = 0x02;
    
    /** Horizontal scroll */
    public static final short REL_HWHEEL = 0x06;
    
    /** Vertical scroll (positive = up) */
    public static final short REL_WHEEL = 0x08;
    
    /** Miscellaneous relative */
    public static final short REL_MISC = 0x09;
    
    /** High-resolution vertical scroll (120 units per detent) */
    public static final short REL_WHEEL_HI_RES = 0x0b;
    
    /** High-resolution horizontal scroll */
    public static final short REL_HWHEEL_HI_RES = 0x0c;

    // ========================================================================
    // Key/Button Codes (KEY_*, BTN_*)
    // ========================================================================

    // --- Keyboard Keys ---
    
    public static final short KEY_ESC = 1;
    public static final short KEY_1 = 2;
    public static final short KEY_2 = 3;
    public static final short KEY_3 = 4;
    public static final short KEY_4 = 5;
    public static final short KEY_5 = 6;
    public static final short KEY_6 = 7;
    public static final short KEY_7 = 8;
    public static final short KEY_8 = 9;
    public static final short KEY_9 = 10;
    public static final short KEY_0 = 11;
    public static final short KEY_MINUS = 12;
    public static final short KEY_EQUAL = 13;
    public static final short KEY_BACKSPACE = 14;
    public static final short KEY_TAB = 15;
    public static final short KEY_Q = 16;
    public static final short KEY_W = 17;
    public static final short KEY_E = 18;
    public static final short KEY_R = 19;
    public static final short KEY_T = 20;
    public static final short KEY_Y = 21;
    public static final short KEY_U = 22;
    public static final short KEY_I = 23;
    public static final short KEY_O = 24;
    public static final short KEY_P = 25;
    public static final short KEY_LEFTBRACE = 26;
    public static final short KEY_RIGHTBRACE = 27;
    public static final short KEY_ENTER = 28;
    public static final short KEY_LEFTCTRL = 29;
    public static final short KEY_A = 30;
    public static final short KEY_S = 31;
    public static final short KEY_D = 32;
    public static final short KEY_F = 33;
    public static final short KEY_G = 34;
    public static final short KEY_H = 35;
    public static final short KEY_J = 36;
    public static final short KEY_K = 37;
    public static final short KEY_L = 38;
    public static final short KEY_SEMICOLON = 39;
    public static final short KEY_APOSTROPHE = 40;
    public static final short KEY_GRAVE = 41;
    public static final short KEY_LEFTSHIFT = 42;
    public static final short KEY_BACKSLASH = 43;
    public static final short KEY_Z = 44;
    public static final short KEY_X = 45;
    public static final short KEY_C = 46;
    public static final short KEY_V = 47;
    public static final short KEY_B = 48;
    public static final short KEY_N = 49;
    public static final short KEY_M = 50;
    public static final short KEY_COMMA = 51;
    public static final short KEY_DOT = 52;
    public static final short KEY_SLASH = 53;
    public static final short KEY_RIGHTSHIFT = 54;
    public static final short KEY_KPASTERISK = 55;
    public static final short KEY_LEFTALT = 56;
    public static final short KEY_SPACE = 57;
    public static final short KEY_CAPSLOCK = 58;
    public static final short KEY_F1 = 59;
    public static final short KEY_F2 = 60;
    public static final short KEY_F3 = 61;
    public static final short KEY_F4 = 62;
    public static final short KEY_F5 = 63;
    public static final short KEY_F6 = 64;
    public static final short KEY_F7 = 65;
    public static final short KEY_F8 = 66;
    public static final short KEY_F9 = 67;
    public static final short KEY_F10 = 68;
    public static final short KEY_NUMLOCK = 69;
    public static final short KEY_SCROLLLOCK = 70;
    public static final short KEY_KP7 = 71;
    public static final short KEY_KP8 = 72;
    public static final short KEY_KP9 = 73;
    public static final short KEY_KPMINUS = 74;
    public static final short KEY_KP4 = 75;
    public static final short KEY_KP5 = 76;
    public static final short KEY_KP6 = 77;
    public static final short KEY_KPPLUS = 78;
    public static final short KEY_KP1 = 79;
    public static final short KEY_KP2 = 80;
    public static final short KEY_KP3 = 81;
    public static final short KEY_KP0 = 82;
    public static final short KEY_KPDOT = 83;
    public static final short KEY_F11 = 87;
    public static final short KEY_F12 = 88;
    public static final short KEY_KPENTER = 96;
    public static final short KEY_RIGHTCTRL = 97;
    public static final short KEY_KPSLASH = 98;
    public static final short KEY_SYSRQ = 99;
    public static final short KEY_RIGHTALT = 100;
    public static final short KEY_HOME = 102;
    public static final short KEY_UP = 103;
    public static final short KEY_PAGEUP = 104;
    public static final short KEY_LEFT = 105;
    public static final short KEY_RIGHT = 106;
    public static final short KEY_END = 107;
    public static final short KEY_DOWN = 108;
    public static final short KEY_PAGEDOWN = 109;
    public static final short KEY_INSERT = 110;
    public static final short KEY_DELETE = 111;
    public static final short KEY_PAUSE = 119;
    public static final short KEY_LEFTMETA = 125;
    public static final short KEY_RIGHTMETA = 126;

    // --- Mouse Buttons ---
    
    /** Base value for mouse buttons */
    public static final short BTN_MOUSE = 0x110;
    
    /** Left mouse button */
    public static final short BTN_LEFT = 0x110;
    
    /** Right mouse button */
    public static final short BTN_RIGHT = 0x111;
    
    /** Middle mouse button */
    public static final short BTN_MIDDLE = 0x112;
    
    /** Side button (browser back) */
    public static final short BTN_SIDE = 0x113;
    
    /** Extra button (browser forward) */
    public static final short BTN_EXTRA = 0x114;
    
    /** Forward button */
    public static final short BTN_FORWARD = 0x115;
    
    /** Back button */
    public static final short BTN_BACK = 0x116;
    
    /** Task button */
    public static final short BTN_TASK = 0x117;

    // ========================================================================
    // UInput ioctl Commands
    // ========================================================================

    /**
     * Set event type bit: ioctl(fd, UI_SET_EVBIT, EV_*)
     * _IOW('U', 100, int) = 0x40045564
     */
    public static final int UI_SET_EVBIT = 0x40045564;

    /**
     * Set key/button bit: ioctl(fd, UI_SET_KEYBIT, KEY_*)
     * _IOW('U', 101, int) = 0x40045565
     */
    public static final int UI_SET_KEYBIT = 0x40045565;

    /**
     * Set relative axis bit: ioctl(fd, UI_SET_RELBIT, REL_*)
     * _IOW('U', 102, int) = 0x40045566
     */
    public static final int UI_SET_RELBIT = 0x40045566;

    /**
     * Set absolute axis bit: ioctl(fd, UI_SET_ABSBIT, ABS_*)
     * _IOW('U', 103, int) = 0x40045567
     */
    public static final int UI_SET_ABSBIT = 0x40045567;

    /**
     * Set miscellaneous bit: ioctl(fd, UI_SET_MSCBIT, MSC_*)
     * _IOW('U', 104, int) = 0x40045568
     */
    public static final int UI_SET_MSCBIT = 0x40045568;

    /**
     * Set physical path: ioctl(fd, UI_SET_PHYS, "usb-..../input0")
     * _IOW('U', 108, char[80]) = 0x4050556c
     * Used to make the device appear more like real hardware.
     */
    public static final int UI_SET_PHYS = 0x4050556c;

    /**
     * Setup device (modern API): ioctl(fd, UI_DEV_SETUP, &uinput_setup)
     * _IOW('U', 3, struct uinput_setup) = 0x405c5503
     */
    public static final int UI_DEV_SETUP = 0x405c5503;

    /**
     * Create the device: ioctl(fd, UI_DEV_CREATE)
     * _IO('U', 1) = 0x5501
     */
    public static final int UI_DEV_CREATE = 0x5501;

    /**
     * Destroy the device: ioctl(fd, UI_DEV_DESTROY)
     * _IO('U', 2) = 0x5502
     */
    public static final int UI_DEV_DESTROY = 0x5502;

    // ========================================================================
    // File Open Flags
    // ========================================================================

    /** Write-only access */
    public static final int O_WRONLY = 1;
    
    /** Non-blocking I/O */
    public static final int O_NONBLOCK = 2048;

    // ========================================================================
    // Bus Types (for uinput_setup.id.bustype)
    // ========================================================================

    /** USB bus */
    public static final short BUS_USB = 0x03;
    
    /** Bluetooth bus */
    public static final short BUS_BLUETOOTH = 0x05;
    
    /** Virtual/software bus */
    public static final short BUS_VIRTUAL = 0x06;

    // ========================================================================
    // Constants for uinput_setup structure
    // ========================================================================

    /** Maximum device name length */
    public static final int UINPUT_MAX_NAME_SIZE = 80;

    // ========================================================================
    // Scroll Constants
    // ========================================================================

    /** Standard wheel detent value for high-resolution scroll */
    public static final int WHEEL_HI_RES_DETENT = 120;
}
