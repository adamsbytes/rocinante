package com.rocinante.input.uinput;

import lombok.Builder;
import lombok.Getter;

import java.util.Set;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * Exact capability specification for a mouse device.
 * 
 * These match real hardware capabilities to avoid fingerprinting.
 * Data sourced from Linux HID drivers and device specifications.
 */
@Getter
@Builder
public class MouseCapabilities {

    /**
     * Supported buttons (BTN_* codes).
     */
    private final Set<Short> buttons;

    /**
     * Supported relative axes (REL_* codes).
     */
    private final Set<Short> relativeAxes;

    /**
     * Whether device supports MSC_SCAN events.
     */
    private final boolean supportsMscScan;

    /**
     * Supported polling rates in Hz.
     * Real gaming mice typically support multiple rates (125, 250, 500, 1000).
     */
    private final int[] supportedPollingRates;

    /**
     * Default polling rate in Hz.
     */
    private final int defaultPollingRate;

    /**
     * Number of DPI stages the mouse supports.
     * Used for realism - doesn't affect uinput but helps with profile consistency.
     */
    private final int dpiStages;

    // ========================================================================
    // Pre-built Capability Profiles
    // ========================================================================

    /**
     * Logitech G502 HERO Gaming Mouse
     * - 11 programmable buttons
     * - Tilt wheel (horizontal scroll)
     * - High-res scroll (Logitech's infinite scroll)
     * - 100-25600 DPI, 5 DPI stages
     * - Up to 1000Hz polling
     */
    public static final MouseCapabilities LOGITECH_G502 = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA,      // Side buttons (back/forward)
                    BTN_FORWARD, BTN_BACK,    // Additional side buttons
                    BTN_TASK                   // DPI shift button
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL, REL_HWHEEL,              // Standard + tilt wheel
                    REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES // High-res scroll
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * Logitech G502 LIGHTSPEED Wireless
     * Same capabilities as wired G502 HERO
     */
    public static final MouseCapabilities LOGITECH_G502_LIGHTSPEED = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA,
                    BTN_FORWARD, BTN_BACK,
                    BTN_TASK
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL, REL_HWHEEL,
                    REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * Logitech G Pro Wireless
     * - 8 programmable buttons (4 side buttons removable)
     * - No tilt wheel
     * - 100-25600 DPI, 5 DPI stages
     */
    public static final MouseCapabilities LOGITECH_G_PRO_WIRELESS = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA,
                    BTN_FORWARD, BTN_BACK
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * Logitech G305 Wireless
     * - 6 buttons
     * - No tilt wheel
     * - 200-12000 DPI, 4 DPI stages
     */
    public static final MouseCapabilities LOGITECH_G305 = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(4)
            .build();

    /**
     * Razer DeathAdder V2
     * - 8 programmable buttons
     * - No tilt wheel
     * - 100-20000 DPI, 5 DPI stages
     * - Focus+ optical sensor
     */
    public static final MouseCapabilities RAZER_DEATHADDER_V2 = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA,
                    BTN_FORWARD, BTN_BACK
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * Razer DeathAdder Essential
     * - 5 buttons (basic model)
     * - No high-res scroll
     * - 6400 DPI max
     */
    public static final MouseCapabilities RAZER_DEATHADDER_ESSENTIAL = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * Razer Viper Mini
     * - 6 buttons
     * - No tilt wheel
     * - 8500 DPI max
     */
    public static final MouseCapabilities RAZER_VIPER_MINI = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * SteelSeries Rival 3
     * - 6 buttons
     * - 8500 DPI TrueMove Core sensor
     */
    public static final MouseCapabilities STEELSERIES_RIVAL_3 = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * SteelSeries Rival 310
     * - 6 buttons
     * - 12000 DPI TrueMove3 sensor
     */
    public static final MouseCapabilities STEELSERIES_RIVAL_310 = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(2)
            .build();

    /**
     * Corsair Harpoon RGB
     * - 6 buttons
     * - 6000 DPI
     */
    public static final MouseCapabilities CORSAIR_HARPOON = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(5)
            .build();

    /**
     * Corsair M55 RGB Pro
     * - 8 buttons (ambidextrous with removable side buttons)
     * - 12400 DPI
     */
    public static final MouseCapabilities CORSAIR_M55 = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA,
                    BTN_FORWARD, BTN_BACK
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(4)
            .build();

    /**
     * HyperX Pulsefire Core
     * - 7 buttons
     * - 6200 DPI Pixart 3327 sensor
     */
    public static final MouseCapabilities HYPERX_PULSEFIRE_CORE = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
                    BTN_SIDE, BTN_EXTRA
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250, 500, 1000})
            .defaultPollingRate(1000)
            .dpiStages(4)
            .build();

    /**
     * Steam Deck Controller (mouse mode)
     * - Basic mouse emulation from touchpad/trackpad
     * - No extra buttons in mouse mode
     */
    public static final MouseCapabilities STEAMDECK_MOUSE = MouseCapabilities.builder()
            .buttons(Set.of(
                    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE
            ))
            .relativeAxes(Set.of(
                    REL_X, REL_Y,
                    REL_WHEEL,
                    REL_WHEEL_HI_RES
            ))
            .supportsMscScan(true)
            .supportedPollingRates(new int[]{125, 250})
            .defaultPollingRate(125)
            .dpiStages(1)
            .build();
}
