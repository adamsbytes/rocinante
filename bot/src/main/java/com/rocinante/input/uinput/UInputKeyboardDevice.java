package com.rocinante.input.uinput;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * UInput virtual keyboard device.
 * 
 * Implements keyboard operations via kernel-level event injection:
 * - Key press/release (EV_KEY)
 * - Modifier key handling (Shift, Ctrl, Alt)
 * 
 * All key codes are translated from AWT KeyEvent codes to Linux KEY_* codes
 * using KeyCodeMapper.
 * 
 * Events are emitted at a realistic polling rate to match
 * the behavior of real hardware.
 */
@Slf4j
public class UInputKeyboardDevice extends UInputDevice {

    /**
     * Create a new virtual keyboard device.
     * 
     * @param preset the device preset (must be a KEYBOARD type)
     * @param pollingRate the polling rate in Hz
     * @param profileId unique profile identifier for deterministic physical path generation
     */
    public UInputKeyboardDevice(DevicePreset preset, int pollingRate, String profileId) {
        super(preset, pollingRate, profileId);
        
        if (preset.getDeviceType() != DeviceType.KEYBOARD) {
            throw new IllegalArgumentException("Preset must be a KEYBOARD type: " + preset.name());
        }
        
        log.debug("UInputKeyboardDevice created with preset: {}, polling: {}Hz", preset.name(), pollingRate);
    }

    /**
     * Create a new virtual keyboard device with default polling rate from preset.
     * 
     * @param preset the device preset (must be a KEYBOARD type)
     * @param profileId unique profile identifier
     */
    public UInputKeyboardDevice(DevicePreset preset, String profileId) {
        this(preset, preset.getDefaultPollingRate(), profileId);
    }

    @Override
    protected void configureCapabilities() {
        KeyboardCapabilities caps = preset.getKeyboardCapabilities();
        if (caps == null) {
            throw new IllegalStateException("Keyboard preset missing capabilities: " + preset.name());
        }

        // Enable key events
        setEvBit(EV_KEY);
        
        // Enable miscellaneous events for MSC_SCAN if supported
        if (caps.isSupportsMscScan()) {
            setEvBit(EV_MSC);
            setMscBit(MSC_SCAN);
        }
        
        // Set key capabilities based on the device's supported keys
        Set<Short> supportedKeys = caps.getSupportedKeys();
        for (Short key : supportedKeys) {
            setKeyBit(key);
        }
    }

    /**
     * Press a key (down).
     * Emits MSC_SCAN before KEY event like real keyboards do.
     * 
     * @param linuxKeyCode the Linux KEY_* code
     */
    public void pressKey(short linuxKeyCode) {
        KeyboardCapabilities caps = preset.getKeyboardCapabilities();
        if (caps != null && caps.isSupportsMscScan()) {
            // Real keyboards emit scancode before keycode
            queueEvent(EV_MSC, MSC_SCAN, linuxKeyCode);
        }
        queueEvent(EV_KEY, linuxKeyCode, 1);
        // Force immediate emission for key events
        flushEvents();
    }

    /**
     * Release a key (up).
     * Emits MSC_SCAN before KEY event like real keyboards do.
     * 
     * @param linuxKeyCode the Linux KEY_* code
     */
    public void releaseKey(short linuxKeyCode) {
        KeyboardCapabilities caps = preset.getKeyboardCapabilities();
        if (caps != null && caps.isSupportsMscScan()) {
            // Real keyboards emit scancode before keycode
            queueEvent(EV_MSC, MSC_SCAN, linuxKeyCode);
        }
        queueEvent(EV_KEY, linuxKeyCode, 0);
        // Force immediate emission for key events
        flushEvents();
    }

    /**
     * Press and release a key.
     * 
     * @param linuxKeyCode the Linux KEY_* code
     * @param holdMs milliseconds to hold the key
     */
    public void tapKey(short linuxKeyCode, long holdMs) throws InterruptedException {
        pressKey(linuxKeyCode);
        Thread.sleep(holdMs);
        releaseKey(linuxKeyCode);
    }

    /**
     * Press a key using AWT KeyEvent code.
     * 
     * @param awtKeyCode the AWT KeyEvent code (e.g., KeyEvent.VK_A)
     * @throws IllegalArgumentException if the key code is not mapped
     */
    public void pressKeyAwt(int awtKeyCode) {
        short linuxCode = KeyCodeMapper.awtToLinux(awtKeyCode);
        if (linuxCode == 0) {
            throw new IllegalArgumentException(
                "Unknown AWT key code: " + awtKeyCode + ". Add mapping to KeyCodeMapper.");
        }
        pressKey(linuxCode);
    }

    /**
     * Release a key using AWT KeyEvent code.
     * 
     * @param awtKeyCode the AWT KeyEvent code
     * @throws IllegalArgumentException if the key code is not mapped
     */
    public void releaseKeyAwt(int awtKeyCode) {
        short linuxCode = KeyCodeMapper.awtToLinux(awtKeyCode);
        if (linuxCode == 0) {
            throw new IllegalArgumentException(
                "Unknown AWT key code: " + awtKeyCode + ". Add mapping to KeyCodeMapper.");
        }
        releaseKey(linuxCode);
    }

    /**
     * Press and release a key using AWT KeyEvent code.
     * 
     * @param awtKeyCode the AWT KeyEvent code
     * @param holdMs milliseconds to hold the key
     * @throws IllegalArgumentException if the key code is not mapped
     */
    public void tapKeyAwt(int awtKeyCode, long holdMs) throws InterruptedException {
        short linuxCode = KeyCodeMapper.awtToLinux(awtKeyCode);
        if (linuxCode == 0) {
            throw new IllegalArgumentException(
                "Unknown AWT key code: " + awtKeyCode + ". Add mapping to KeyCodeMapper.");
        }
        tapKey(linuxCode, holdMs);
    }

    /**
     * Type a character, handling shift for uppercase/symbols.
     * 
     * @param c the character to type
     * @param holdMs hold duration for each key
     * @throws IllegalArgumentException if the character is not mappable
     */
    public void typeChar(char c, long holdMs) throws InterruptedException {
        short[] keys = KeyCodeMapper.charToLinuxKeys(c);
        
        if (keys.length == 0) {
            throw new IllegalArgumentException(
                "Cannot type character: '" + c + "' (code " + (int)c + "). Add mapping to KeyCodeMapper.");
        }
        
        if (keys.length == 2) {
            // Need shift modifier
            pressKey(keys[0]); // Press shift
            Thread.sleep(10);
            tapKey(keys[1], holdMs);
            Thread.sleep(10);
            releaseKey(keys[0]); // Release shift
        } else {
            // No modifier needed
            tapKey(keys[0], holdMs);
        }
    }
}
