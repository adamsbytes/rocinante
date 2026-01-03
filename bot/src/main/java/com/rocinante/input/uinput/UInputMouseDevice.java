package com.rocinante.input.uinput;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * UInput virtual mouse device.
 * 
 * Implements mouse operations via kernel-level event injection:
 * - Relative movement (EV_REL)
 * - Button press/release (EV_KEY)
 * - Scroll wheel (REL_WHEEL, REL_WHEEL_HI_RES)
 * 
 * Position is tracked internally and synced with X11 cursor
 * before each movement operation to handle drift.
 * 
 * Events are emitted at a realistic polling rate to match
 * the behavior of real hardware.
 */
@Slf4j
public class UInputMouseDevice extends UInputDevice {

    /**
     * Tracked cursor position (updated after each move).
     */
    private int lastX;
    private int lastY;

    /**
     * Whether position has been synced since device creation.
     */
    private boolean positionSynced = false;

    /**
     * Create a new virtual mouse device.
     * 
     * @param preset the device preset (must be a MOUSE type)
     * @param pollingRate the polling rate in Hz
     * @param profileId unique profile identifier for deterministic physical path generation
     */
    public UInputMouseDevice(DevicePreset preset, int pollingRate, String profileId) {
        super(preset, pollingRate, profileId);
        
        if (preset.getDeviceType() != DeviceType.MOUSE) {
            throw new IllegalArgumentException("Preset must be a MOUSE type: " + preset.name());
        }
        
        log.debug("UInputMouseDevice created with preset: {}, polling: {}Hz", preset.name(), pollingRate);
    }

    /**
     * Create a new virtual mouse device with default polling rate from preset.
     * 
     * @param preset the device preset (must be a MOUSE type)
     * @param profileId unique profile identifier
     */
    public UInputMouseDevice(DevicePreset preset, String profileId) {
        this(preset, preset.getDefaultPollingRate(), profileId);
    }

    @Override
    protected void configureCapabilities() {
        MouseCapabilities caps = preset.getMouseCapabilities();
        if (caps == null) {
            throw new IllegalStateException("Mouse preset missing capabilities: " + preset.name());
        }

        // Enable relative axis events based on device capabilities
        setEvBit(EV_REL);
        for (Short relAxis : caps.getRelativeAxes()) {
            setRelBit(relAxis);
        }
        
        // Enable button events based on device capabilities
        setEvBit(EV_KEY);
        for (Short button : caps.getButtons()) {
            setKeyBit(button);
        }
        
        // Enable miscellaneous events for MSC_SCAN if supported
        if (caps.isSupportsMscScan()) {
            setEvBit(EV_MSC);
            setMscBit(MSC_SCAN);
        }
    }

    /**
     * Sync internal position tracking with actual X11 cursor position.
     * Call this before starting a movement operation.
     * 
     * @throws IllegalStateException if the cursor position cannot be determined
     */
    public void syncPosition() {
        try {
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            if (pointerInfo == null) {
                throw new IllegalStateException("PointerInfo is null - no display available?");
            }
            Point p = pointerInfo.getLocation();
            if (p == null) {
                throw new IllegalStateException("PointerInfo.getLocation() returned null");
            }
            lastX = p.x;
            lastY = p.y;
            positionSynced = true;
            log.trace("Position synced: ({}, {})", lastX, lastY);
        } catch (HeadlessException e) {
            throw new IllegalStateException("Cannot sync cursor position in headless mode", e);
        }
    }

    /**
     * Get the last known cursor position.
     * 
     * @return Point representing (x, y) screen coordinates
     */
    public Point getPosition() {
        return new Point(lastX, lastY);
    }

    /**
     * Move cursor to absolute screen coordinates.
     * Calculates delta from last position and emits relative events.
     * Events are queued for emission at the device's polling rate.
     * 
     * @param x target X coordinate
     * @param y target Y coordinate
     */
    public void moveTo(int x, int y) {
        int dx = x - lastX;
        int dy = y - lastY;
        
        if (dx != 0 || dy != 0) {
            if (dx != 0) {
                queueEvent(EV_REL, REL_X, dx);
            }
            if (dy != 0) {
                queueEvent(EV_REL, REL_Y, dy);
            }
            // SYN_REPORT is handled by polling thread
            
            lastX = x;
            lastY = y;
        }
    }

    /**
     * Move cursor by relative delta.
     * Events are queued for emission at the device's polling rate.
     * 
     * @param dx X delta (positive = right)
     * @param dy Y delta (positive = down)
     */
    public void moveBy(int dx, int dy) {
        if (dx != 0 || dy != 0) {
            if (dx != 0) {
                queueEvent(EV_REL, REL_X, dx);
            }
            if (dy != 0) {
                queueEvent(EV_REL, REL_Y, dy);
            }
            // SYN_REPORT is handled by polling thread
            
            lastX += dx;
            lastY += dy;
        }
    }

    /**
     * Press a mouse button.
     * Emits MSC_SCAN before button event like real mice do.
     * 
     * @param button the button code (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE)
     */
    public void pressButton(short button) {
        MouseCapabilities caps = preset.getMouseCapabilities();
        if (caps != null && caps.isSupportsMscScan()) {
            // Real mice emit scancode before button event
            // The scancode for buttons is typically 0x90001 + (button - BTN_LEFT)
            int scancode = 0x90001 + (button - BTN_LEFT);
            queueEvent(EV_MSC, MSC_SCAN, scancode);
        }
        queueEvent(EV_KEY, button, 1);
        // Force immediate emission for button events
        flushEvents();
    }

    /**
     * Release a mouse button.
     * Emits MSC_SCAN before button event like real mice do.
     * 
     * @param button the button code (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE)
     */
    public void releaseButton(short button) {
        MouseCapabilities caps = preset.getMouseCapabilities();
        if (caps != null && caps.isSupportsMscScan()) {
            // Real mice emit scancode before button event
            int scancode = 0x90001 + (button - BTN_LEFT);
            queueEvent(EV_MSC, MSC_SCAN, scancode);
        }
        queueEvent(EV_KEY, button, 0);
        // Force immediate emission for button events
        flushEvents();
    }

    /**
     * Press and release a mouse button (click).
     * 
     * @param button the button code
     * @param holdMs milliseconds to hold the button
     */
    public void click(short button, long holdMs) throws InterruptedException {
        pressButton(button);
        Thread.sleep(holdMs);
        releaseButton(button);
    }

    /**
     * Scroll the mouse wheel.
     * Uses high-resolution scroll if supported.
     * 
     * @param amount number of scroll notches (positive = up/away from user)
     */
    public void scroll(int amount) {
        if (amount == 0) return;
        
        MouseCapabilities caps = preset.getMouseCapabilities();
        boolean hasHiRes = caps != null && 
            caps.getRelativeAxes().contains(REL_WHEEL_HI_RES);
        
        if (hasHiRes) {
            // High-res scroll: 120 units per standard detent
            queueEvent(EV_REL, REL_WHEEL_HI_RES, amount * WHEEL_HI_RES_DETENT);
            // Also emit standard wheel event for compatibility
            queueEvent(EV_REL, REL_WHEEL, amount);
        } else {
            queueEvent(EV_REL, REL_WHEEL, amount);
        }
        // Force immediate for scroll
        flushEvents();
    }

    /**
     * Scroll horizontally.
     * 
     * @param amount number of scroll notches (positive = right)
     */
    public void scrollHorizontal(int amount) {
        if (amount == 0) return;
        
        MouseCapabilities caps = preset.getMouseCapabilities();
        boolean hasHiRes = caps != null && 
            caps.getRelativeAxes().contains(REL_HWHEEL_HI_RES);
        
        if (hasHiRes) {
            queueEvent(EV_REL, REL_HWHEEL_HI_RES, amount * WHEEL_HI_RES_DETENT);
        }
        queueEvent(EV_REL, REL_HWHEEL, amount);
        // Force immediate for scroll
        flushEvents();
    }

    /**
     * Convert AWT InputEvent button mask to Linux BTN_* code.
     * 
     * @param awtButtonMask the AWT button mask (e.g., InputEvent.BUTTON1_DOWN_MASK)
     * @return the Linux button code
     * @throws IllegalArgumentException if the button mask is not recognized
     */
    public static short awtButtonToLinux(int awtButtonMask) {
        return switch (awtButtonMask) {
            case java.awt.event.InputEvent.BUTTON1_DOWN_MASK -> BTN_LEFT;
            case java.awt.event.InputEvent.BUTTON2_DOWN_MASK -> BTN_MIDDLE;
            case java.awt.event.InputEvent.BUTTON3_DOWN_MASK -> BTN_RIGHT;
            default -> throw new IllegalArgumentException(
                "Unknown AWT button mask: " + awtButtonMask);
        };
    }
}
