package com.rocinante.input.uinput;

import lombok.Builder;
import lombok.Getter;

import java.util.Set;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * Exact capability specification for a keyboard device.
 * 
 * These match real hardware capabilities to avoid fingerprinting.
 */
@Getter
@Builder
public class KeyboardCapabilities {

    /**
     * Whether the keyboard has a numpad (full-size vs TKL/60%).
     */
    private final boolean hasNumpad;

    /**
     * Whether the keyboard has media keys (play/pause, volume, etc.).
     */
    private final boolean hasMediaKeys;

    /**
     * Whether the keyboard has extra macro/G-keys.
     */
    private final boolean hasMacroKeys;

    /**
     * Number of macro keys (if any).
     */
    private final int macroKeyCount;

    /**
     * Whether device supports LED events (keyboard LEDs like Caps Lock indicator).
     */
    private final boolean supportsLeds;

    /**
     * Whether device supports key repeat events.
     */
    private final boolean supportsRepeat;

    /**
     * Whether device supports MSC_SCAN events.
     */
    private final boolean supportsMscScan;

    /**
     * Supported polling rates in Hz.
     */
    private final int[] supportedPollingRates;

    /**
     * Default polling rate in Hz.
     */
    private final int defaultPollingRate;

    /**
     * Keyboard form factor: FULL, TKL (tenkeyless), 60%, 65%, 75%
     */
    private final String formFactor;

    // ========================================================================
    // Standard Key Sets
    // ========================================================================

    /**
     * Standard full keyboard keys (104-key US layout).
     */
    public static final Set<Short> STANDARD_FULL_KEYS = Set.of(
            // Letters
            KEY_Q, KEY_W, KEY_E, KEY_R, KEY_T, KEY_Y, KEY_U, KEY_I, KEY_O, KEY_P,
            KEY_A, KEY_S, KEY_D, KEY_F, KEY_G, KEY_H, KEY_J, KEY_K, KEY_L,
            KEY_Z, KEY_X, KEY_C, KEY_V, KEY_B, KEY_N, KEY_M,
            // Numbers
            KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9, KEY_0,
            // Function keys
            KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6,
            KEY_F7, KEY_F8, KEY_F9, KEY_F10, KEY_F11, KEY_F12,
            // Modifiers
            KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTCTRL, KEY_RIGHTCTRL,
            KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA, KEY_RIGHTMETA,
            // Special keys
            KEY_ESC, KEY_TAB, KEY_ENTER, KEY_BACKSPACE, KEY_SPACE,
            KEY_DELETE, KEY_INSERT, KEY_HOME, KEY_END, KEY_PAGEUP, KEY_PAGEDOWN,
            KEY_CAPSLOCK, KEY_NUMLOCK, KEY_SCROLLLOCK, KEY_PAUSE, KEY_SYSRQ,
            // Arrow keys
            KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
            // Punctuation
            KEY_MINUS, KEY_EQUAL, KEY_LEFTBRACE, KEY_RIGHTBRACE,
            KEY_SEMICOLON, KEY_APOSTROPHE, KEY_GRAVE, KEY_BACKSLASH,
            KEY_COMMA, KEY_DOT, KEY_SLASH,
            // Numpad
            KEY_KP0, KEY_KP1, KEY_KP2, KEY_KP3, KEY_KP4,
            KEY_KP5, KEY_KP6, KEY_KP7, KEY_KP8, KEY_KP9,
            KEY_KPENTER, KEY_KPSLASH, KEY_KPASTERISK, KEY_KPMINUS, KEY_KPPLUS, KEY_KPDOT
    );

    /**
     * TKL keyboard keys (no numpad).
     */
    public static final Set<Short> STANDARD_TKL_KEYS = Set.of(
            // Letters
            KEY_Q, KEY_W, KEY_E, KEY_R, KEY_T, KEY_Y, KEY_U, KEY_I, KEY_O, KEY_P,
            KEY_A, KEY_S, KEY_D, KEY_F, KEY_G, KEY_H, KEY_J, KEY_K, KEY_L,
            KEY_Z, KEY_X, KEY_C, KEY_V, KEY_B, KEY_N, KEY_M,
            // Numbers
            KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9, KEY_0,
            // Function keys
            KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6,
            KEY_F7, KEY_F8, KEY_F9, KEY_F10, KEY_F11, KEY_F12,
            // Modifiers
            KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTCTRL, KEY_RIGHTCTRL,
            KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA, KEY_RIGHTMETA,
            // Special keys
            KEY_ESC, KEY_TAB, KEY_ENTER, KEY_BACKSPACE, KEY_SPACE,
            KEY_DELETE, KEY_INSERT, KEY_HOME, KEY_END, KEY_PAGEUP, KEY_PAGEDOWN,
            KEY_CAPSLOCK, KEY_SCROLLLOCK, KEY_PAUSE, KEY_SYSRQ,
            // Arrow keys
            KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
            // Punctuation
            KEY_MINUS, KEY_EQUAL, KEY_LEFTBRACE, KEY_RIGHTBRACE,
            KEY_SEMICOLON, KEY_APOSTROPHE, KEY_GRAVE, KEY_BACKSLASH,
            KEY_COMMA, KEY_DOT, KEY_SLASH
    );

    /**
     * Standard keyboard LEDs.
     */
    public static final short LED_NUML = 0x00;
    public static final short LED_CAPSL = 0x01;
    public static final short LED_SCROLLL = 0x02;

    // ========================================================================
    // Pre-built Capability Profiles
    // ========================================================================

    /**
     * Logitech G Pro Gaming Keyboard
     * - TKL form factor (no numpad)
     * - No dedicated macro keys
     * - Up to 1000Hz polling
     * - RGB per-key lighting (not relevant for input device)
     */
    public static final KeyboardCapabilities LOGITECH_G_PRO_KB = KeyboardCapabilities.builder()
            .hasNumpad(false)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("TKL")
            .build();

    /**
     * Logitech G213 Prodigy Gaming Keyboard
     * - Full-size with numpad
     * - Media keys with roller
     * - Membrane keys
     */
    public static final KeyboardCapabilities LOGITECH_G213 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * Logitech G413 Mechanical Gaming Keyboard
     * - Full-size with numpad
     * - Romer-G mechanical switches
     */
    public static final KeyboardCapabilities LOGITECH_G413 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * Razer BlackWidow V3
     * - Full-size with numpad
     * - Green mechanical switches
     * - Media keys with multi-function dial
     */
    public static final KeyboardCapabilities RAZER_BLACKWIDOW_V3 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * Razer Huntsman Mini
     * - 60% form factor
     * - No numpad, no F-row, no nav cluster
     * - Optical switches
     */
    public static final KeyboardCapabilities RAZER_HUNTSMAN_MINI = KeyboardCapabilities.builder()
            .hasNumpad(false)
            .hasMediaKeys(false)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("60%")
            .build();

    /**
     * Razer Ornata V2
     * - Full-size with numpad
     * - Mecha-membrane switches
     * - Media keys with digital dial
     */
    public static final KeyboardCapabilities RAZER_ORNATA_V2 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * SteelSeries Apex 3
     * - Full-size with numpad
     * - Whisper-quiet switches (membrane)
     * - Media keys with OLED display
     */
    public static final KeyboardCapabilities STEELSERIES_APEX_3 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * SteelSeries Apex Pro
     * - Full-size with numpad
     * - OmniPoint adjustable switches
     * - OLED Smart Display
     */
    public static final KeyboardCapabilities STEELSERIES_APEX_PRO = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * Corsair K55 RGB
     * - Full-size with numpad
     * - Membrane keys
     * - 6 programmable macro keys
     */
    public static final KeyboardCapabilities CORSAIR_K55 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(true)
            .macroKeyCount(6)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * Corsair K70 RGB MK.2
     * - Full-size with numpad
     * - Cherry MX mechanical switches
     * - Dedicated media controls
     */
    public static final KeyboardCapabilities CORSAIR_K70 = KeyboardCapabilities.builder()
            .hasNumpad(true)
            .hasMediaKeys(true)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("FULL")
            .build();

    /**
     * HyperX Alloy Origins Core
     * - TKL form factor
     * - HyperX mechanical switches
     */
    public static final KeyboardCapabilities HYPERX_ALLOY_ORIGINS = KeyboardCapabilities.builder()
            .hasNumpad(false)
            .hasMediaKeys(false)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(true)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .formFactor("TKL")
            .build();

    /**
     * Steam Deck Controller (keyboard mode)
     * - Virtual keyboard from touchscreen/touchpads
     * - Limited key set
     */
    public static final KeyboardCapabilities STEAMDECK_KEYBOARD = KeyboardCapabilities.builder()
            .hasNumpad(false)
            .hasMediaKeys(false)
            .hasMacroKeys(false)
            .macroKeyCount(0)
            .supportsLeds(false)
            .supportsRepeat(true)
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250})
            .defaultPollingRate(125)
            .formFactor("VIRTUAL")
            .build();

    /**
     * Get the set of supported keys based on form factor.
     */
    public Set<Short> getSupportedKeys() {
        if (hasNumpad) {
            return STANDARD_FULL_KEYS;
        } else {
            return STANDARD_TKL_KEYS;
        }
    }
}
