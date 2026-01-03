package com.rocinante.input.uinput;

import lombok.Getter;

import java.util.Random;

/**
 * Preset device profiles for uinput virtual devices.
 * Each preset defines realistic vendor/product IDs, device names,
 * and exact hardware capabilities that match real gaming peripherals.
 * 
 * Used to spoof hardware identity for anti-detection.
 */
@Getter
public enum DevicePreset {

    // ========================================================================
    // Steam Deck (matches pthreads_ext.c spoofing)
    // ========================================================================

    STEAMDECK_MOUSE(
        "Valve Software Steam Deck Controller Mouse",
        (short) 0x28de,  // Valve Corporation
        (short) 0x1205,  // Steam Deck Controller
        DeviceType.MOUSE,
        MouseCapabilities.STEAMDECK_MOUSE,
        null
    ),

    STEAMDECK_KEYBOARD(
        "Valve Software Steam Deck Controller",
        (short) 0x28de,
        (short) 0x1205,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.STEAMDECK_KEYBOARD
    ),

    // ========================================================================
    // Logitech Gaming Peripherals
    // ========================================================================

    LOGITECH_G502(
        "Logitech G502 HERO Gaming Mouse",
        (short) 0x046d,  // Logitech
        (short) 0xc08b,  // G502 HERO
        DeviceType.MOUSE,
        MouseCapabilities.LOGITECH_G502,
        null
    ),

    LOGITECH_G502_LIGHTSPEED(
        "Logitech G502 LIGHTSPEED Wireless Gaming Mouse",
        (short) 0x046d,
        (short) 0xc08d,
        DeviceType.MOUSE,
        MouseCapabilities.LOGITECH_G502_LIGHTSPEED,
        null
    ),

    LOGITECH_G_PRO_WIRELESS(
        "Logitech G Pro Wireless Gaming Mouse",
        (short) 0x046d,
        (short) 0xc088,
        DeviceType.MOUSE,
        MouseCapabilities.LOGITECH_G_PRO_WIRELESS,
        null
    ),

    LOGITECH_G_PRO_KB(
        "Logitech G Pro Gaming Keyboard",
        (short) 0x046d,
        (short) 0xc339,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.LOGITECH_G_PRO_KB
    ),

    LOGITECH_G213(
        "Logitech G213 Prodigy Gaming Keyboard",
        (short) 0x046d,
        (short) 0xc336,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.LOGITECH_G213
    ),

    LOGITECH_G413(
        "Logitech G413 Mechanical Gaming Keyboard",
        (short) 0x046d,
        (short) 0xc33a,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.LOGITECH_G413
    ),

    LOGITECH_G305(
        "Logitech G305 Wireless Gaming Mouse",
        (short) 0x046d,
        (short) 0xc081,
        DeviceType.MOUSE,
        MouseCapabilities.LOGITECH_G305,
        null
    ),

    // ========================================================================
    // Razer Peripherals
    // ========================================================================

    RAZER_DEATHADDER_V2(
        "Razer DeathAdder V2",
        (short) 0x1532,  // Razer USA
        (short) 0x0084,
        DeviceType.MOUSE,
        MouseCapabilities.RAZER_DEATHADDER_V2,
        null
    ),

    RAZER_DEATHADDER_ESSENTIAL(
        "Razer DeathAdder Essential",
        (short) 0x1532,
        (short) 0x006e,
        DeviceType.MOUSE,
        MouseCapabilities.RAZER_DEATHADDER_ESSENTIAL,
        null
    ),

    RAZER_VIPER_MINI(
        "Razer Viper Mini",
        (short) 0x1532,
        (short) 0x008a,
        DeviceType.MOUSE,
        MouseCapabilities.RAZER_VIPER_MINI,
        null
    ),

    RAZER_BLACKWIDOW_V3(
        "Razer BlackWidow V3",
        (short) 0x1532,
        (short) 0x024e,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.RAZER_BLACKWIDOW_V3
    ),

    RAZER_HUNTSMAN_MINI(
        "Razer Huntsman Mini",
        (short) 0x1532,
        (short) 0x0257,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.RAZER_HUNTSMAN_MINI
    ),

    RAZER_ORNATA_V2(
        "Razer Ornata V2",
        (short) 0x1532,
        (short) 0x025d,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.RAZER_ORNATA_V2
    ),

    // ========================================================================
    // SteelSeries Peripherals
    // ========================================================================

    STEELSERIES_RIVAL_3(
        "SteelSeries Rival 3",
        (short) 0x1038,  // SteelSeries
        (short) 0x1824,
        DeviceType.MOUSE,
        MouseCapabilities.STEELSERIES_RIVAL_3,
        null
    ),

    STEELSERIES_RIVAL_310(
        "SteelSeries Rival 310",
        (short) 0x1038,
        (short) 0x1720,
        DeviceType.MOUSE,
        MouseCapabilities.STEELSERIES_RIVAL_310,
        null
    ),

    STEELSERIES_APEX_3(
        "SteelSeries Apex 3",
        (short) 0x1038,
        (short) 0x1612,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.STEELSERIES_APEX_3
    ),

    STEELSERIES_APEX_PRO(
        "SteelSeries Apex Pro",
        (short) 0x1038,
        (short) 0x1610,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.STEELSERIES_APEX_PRO
    ),

    // ========================================================================
    // Corsair Peripherals
    // ========================================================================

    CORSAIR_HARPOON(
        "Corsair HARPOON RGB",
        (short) 0x1b1c,  // Corsair
        (short) 0x1b3c,
        DeviceType.MOUSE,
        MouseCapabilities.CORSAIR_HARPOON,
        null
    ),

    CORSAIR_M55(
        "Corsair M55 RGB PRO",
        (short) 0x1b1c,
        (short) 0x1b70,
        DeviceType.MOUSE,
        MouseCapabilities.CORSAIR_M55,
        null
    ),

    CORSAIR_K55(
        "Corsair K55 RGB",
        (short) 0x1b1c,
        (short) 0x1b3d,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.CORSAIR_K55
    ),

    CORSAIR_K70(
        "Corsair K70 RGB MK.2",
        (short) 0x1b1c,
        (short) 0x1b49,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.CORSAIR_K70
    ),

    // ========================================================================
    // HyperX Peripherals
    // ========================================================================

    HYPERX_PULSEFIRE_CORE(
        "HyperX Pulsefire Core",
        (short) 0x0951,  // Kingston/HyperX
        (short) 0x16de,
        DeviceType.MOUSE,
        MouseCapabilities.HYPERX_PULSEFIRE_CORE,
        null
    ),

    HYPERX_ALLOY_ORIGINS(
        "HyperX Alloy Origins Core",
        (short) 0x0951,
        (short) 0x16e5,
        DeviceType.KEYBOARD,
        null,
        KeyboardCapabilities.HYPERX_ALLOY_ORIGINS
    );

    // NOTE: Generic devices removed - we only use real branded peripherals
    // for maximum realism and to avoid fingerprinting based on "generic" identifiers.

    // ========================================================================
    // Fields
    // ========================================================================

    private final String name;
    private final short vendorId;
    private final short productId;
    private final DeviceType deviceType;
    private final MouseCapabilities mouseCapabilities;
    private final KeyboardCapabilities keyboardCapabilities;

    DevicePreset(String name, short vendorId, short productId, 
                 DeviceType deviceType, 
                 MouseCapabilities mouseCapabilities,
                 KeyboardCapabilities keyboardCapabilities) {
        this.name = name;
        this.vendorId = vendorId;
        this.productId = productId;
        this.deviceType = deviceType;
        this.mouseCapabilities = mouseCapabilities;
        this.keyboardCapabilities = keyboardCapabilities;
    }

    /**
     * Check if this preset supports high-resolution scroll.
     * Convenience method that checks the mouse capabilities.
     */
    public boolean isSupportsHighResScroll() {
        if (mouseCapabilities == null) {
            return false;
        }
        return mouseCapabilities.getRelativeAxes().contains(LinuxInputConstants.REL_WHEEL_HI_RES);
    }

    /**
     * Get the default polling rate for this device.
     */
    public int getDefaultPollingRate() {
        if (mouseCapabilities != null) {
            return mouseCapabilities.getDefaultPollingRate();
        }
        if (keyboardCapabilities != null) {
            return keyboardCapabilities.getDefaultPollingRate();
        }
        return 1000; // Safe default
    }

    /**
     * Get supported polling rates for this device.
     */
    public int[] getSupportedPollingRates() {
        if (mouseCapabilities != null) {
            return mouseCapabilities.getSupportedPollingRates();
        }
        if (keyboardCapabilities != null) {
            return keyboardCapabilities.getSupportedPollingRates();
        }
        return new int[]{125, 250, 500, 1000};
    }

    /**
     * Select a random supported polling rate for this device.
     * Gaming devices typically use 1000Hz, budget devices use lower rates.
     * Weighted toward default rate.
     */
    public int selectPollingRate(Random random) {
        int[] rates = getSupportedPollingRates();
        int defaultRate = getDefaultPollingRate();
        
        // 70% chance of default rate, 30% chance of other supported rate
        if (random.nextDouble() < 0.70 || rates.length == 1) {
            return defaultRate;
        }
        
        // Pick a non-default rate
        int idx;
        do {
            idx = random.nextInt(rates.length);
        } while (rates[idx] == defaultRate && rates.length > 1);
        
        return rates[idx];
    }

    // ========================================================================
    // Random Selection Methods
    // ========================================================================

    /**
     * All mouse presets for random selection.
     * Excludes Steam Deck by default (it's a special case).
     */
    private static final DevicePreset[] MOUSE_PRESETS = {
        LOGITECH_G502, LOGITECH_G502_LIGHTSPEED, LOGITECH_G_PRO_WIRELESS, LOGITECH_G305,
        RAZER_DEATHADDER_V2, RAZER_DEATHADDER_ESSENTIAL, RAZER_VIPER_MINI,
        STEELSERIES_RIVAL_3, STEELSERIES_RIVAL_310,
        CORSAIR_HARPOON, CORSAIR_M55,
        HYPERX_PULSEFIRE_CORE
    };

    /**
     * All keyboard presets for random selection.
     * Excludes Steam Deck by default.
     */
    private static final DevicePreset[] KEYBOARD_PRESETS = {
        LOGITECH_G_PRO_KB, LOGITECH_G213, LOGITECH_G413,
        RAZER_BLACKWIDOW_V3, RAZER_HUNTSMAN_MINI, RAZER_ORNATA_V2,
        STEELSERIES_APEX_3, STEELSERIES_APEX_PRO,
        CORSAIR_K55, CORSAIR_K70,
        HYPERX_ALLOY_ORIGINS
    };

    /**
     * All mouse presets including Steam Deck.
     */
    private static final DevicePreset[] ALL_MOUSE_PRESETS = {
        STEAMDECK_MOUSE,
        LOGITECH_G502, LOGITECH_G502_LIGHTSPEED, LOGITECH_G_PRO_WIRELESS, LOGITECH_G305,
        RAZER_DEATHADDER_V2, RAZER_DEATHADDER_ESSENTIAL, RAZER_VIPER_MINI,
        STEELSERIES_RIVAL_3, STEELSERIES_RIVAL_310,
        CORSAIR_HARPOON, CORSAIR_M55,
        HYPERX_PULSEFIRE_CORE
    };

    /**
     * All keyboard presets including Steam Deck.
     */
    private static final DevicePreset[] ALL_KEYBOARD_PRESETS = {
        STEAMDECK_KEYBOARD,
        LOGITECH_G_PRO_KB, LOGITECH_G213, LOGITECH_G413,
        RAZER_BLACKWIDOW_V3, RAZER_HUNTSMAN_MINI, RAZER_ORNATA_V2,
        STEELSERIES_APEX_3, STEELSERIES_APEX_PRO,
        CORSAIR_K55, CORSAIR_K70,
        HYPERX_ALLOY_ORIGINS
    };

    /**
     * Brand groupings for matching peripherals.
     * Format: [brand_name, mouse_presets..., keyboard_presets...]
     */
    private static final DevicePreset[][] BRAND_GROUPS = {
        // Logitech
        {LOGITECH_G502, LOGITECH_G502_LIGHTSPEED, LOGITECH_G_PRO_WIRELESS, LOGITECH_G305,
         LOGITECH_G_PRO_KB, LOGITECH_G213, LOGITECH_G413},
        // Razer
        {RAZER_DEATHADDER_V2, RAZER_DEATHADDER_ESSENTIAL, RAZER_VIPER_MINI,
         RAZER_BLACKWIDOW_V3, RAZER_HUNTSMAN_MINI, RAZER_ORNATA_V2},
        // SteelSeries
        {STEELSERIES_RIVAL_3, STEELSERIES_RIVAL_310,
         STEELSERIES_APEX_3, STEELSERIES_APEX_PRO},
        // Corsair
        {CORSAIR_HARPOON, CORSAIR_M55, CORSAIR_K55, CORSAIR_K70},
        // HyperX
        {HYPERX_PULSEFIRE_CORE, HYPERX_ALLOY_ORIGINS}
    };

    /**
     * Select a random mouse preset (excludes Steam Deck).
     * 
     * @param random the random source
     * @return a random mouse preset
     */
    public static DevicePreset randomMouse(Random random) {
        return MOUSE_PRESETS[random.nextInt(MOUSE_PRESETS.length)];
    }

    /**
     * Select a random keyboard preset (excludes Steam Deck).
     * 
     * @param random the random source
     * @return a random keyboard preset
     */
    public static DevicePreset randomKeyboard(Random random) {
        return KEYBOARD_PRESETS[random.nextInt(KEYBOARD_PRESETS.length)];
    }

    /**
     * Select a random mouse preset (includes all presets).
     * 
     * @param random the random source
     * @return a random mouse preset
     */
    public static DevicePreset randomMouseIncludingAll(Random random) {
        return ALL_MOUSE_PRESETS[random.nextInt(ALL_MOUSE_PRESETS.length)];
    }

    /**
     * Select a random keyboard preset (includes all presets).
     * 
     * @param random the random source
     * @return a random keyboard preset
     */
    public static DevicePreset randomKeyboardIncludingAll(Random random) {
        return ALL_KEYBOARD_PRESETS[random.nextInt(ALL_KEYBOARD_PRESETS.length)];
    }

    /**
     * Select a matching mouse and keyboard pair from the same brand.
     * ~70% chance of same brand, ~30% mixed brands.
     * 
     * @param random the random source
     * @return array of [mousePreset, keyboardPreset]
     */
    public static DevicePreset[] randomMatchingPair(Random random) {
        if (random.nextDouble() < 0.70) {
            // Same brand - pick a brand group
            DevicePreset[] brandGroup = BRAND_GROUPS[random.nextInt(BRAND_GROUPS.length)];
            
            // Split into mice and keyboards
            DevicePreset mouse = null;
            DevicePreset keyboard = null;
            
            // Collect mice and keyboards from this brand
            java.util.List<DevicePreset> mice = new java.util.ArrayList<>();
            java.util.List<DevicePreset> keyboards = new java.util.ArrayList<>();
            
            for (DevicePreset preset : brandGroup) {
                if (preset.deviceType == DeviceType.MOUSE) {
                    mice.add(preset);
                } else {
                    keyboards.add(preset);
                }
            }
            
            mouse = mice.get(random.nextInt(mice.size()));
            keyboard = keyboards.get(random.nextInt(keyboards.size()));
            
            return new DevicePreset[] {mouse, keyboard};
        } else {
            // Mixed brands
            return new DevicePreset[] {
                randomMouse(random),
                randomKeyboard(random)
            };
        }
    }

    /**
     * Get a preset by name (case-insensitive).
     * 
     * @param name the preset name
     * @return the preset, or null if not found
     */
    public static DevicePreset byName(String name) {
        for (DevicePreset preset : values()) {
            if (preset.name().equalsIgnoreCase(name)) {
                return preset;
            }
        }
        return null;
    }
}
