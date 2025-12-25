package com.rocinante.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("rocinante")
public interface RocinanteConfig extends Config
{
    // ========================================================================
    // General Section
    // ========================================================================

    @ConfigSection(
        name = "General",
        description = "General automation settings",
        position = 0
    )
    String generalSection = "general";

    @ConfigItem(
        keyName = "enabled",
        name = "Enable Automation",
        description = "Master toggle for automation",
        section = generalSection,
        position = 0
    )
    default boolean enabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "delayMultiplier",
        name = "Delay Multiplier",
        description = "Global delay multiplier (0.5x - 2.0x)",
        section = generalSection,
        position = 1
    )
    default double delayMultiplier()
    {
        return 1.0;
    }

    // ========================================================================
    // Input Humanization Section
    // ========================================================================

    @ConfigSection(
        name = "Input Humanization",
        description = "Mouse and keyboard humanization settings",
        position = 1
    )
    String inputSection = "input";

    @ConfigItem(
        keyName = "inputHumanizationEnabled",
        name = "Enable Input Humanization",
        description = "Enable humanized mouse and keyboard behavior",
        section = inputSection,
        position = 0
    )
    default boolean inputHumanizationEnabled()
    {
        return true;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
        keyName = "misclickRateMultiplier",
        name = "Misclick Rate",
        description = "Misclick frequency (1=low, 3=normal, 5=high)",
        section = inputSection,
        position = 1
    )
    default int misclickRateMultiplier()
    {
        return 3;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
        keyName = "typoRateMultiplier",
        name = "Typo Rate",
        description = "Typing error frequency (1=low, 3=normal, 5=high)",
        section = inputSection,
        position = 2
    )
    default int typoRateMultiplier()
    {
        return 3;
    }

    @Range(min = 50, max = 150)
    @ConfigItem(
        keyName = "mouseSpeedPercent",
        name = "Mouse Speed",
        description = "Mouse movement speed percentage (50-150%)",
        section = inputSection,
        position = 3
    )
    @Units(Units.PERCENT)
    default int mouseSpeedPercent()
    {
        return 100;
    }

    @Range(min = 50, max = 150)
    @ConfigItem(
        keyName = "typingSpeedPercent",
        name = "Typing Speed",
        description = "Typing speed percentage (50-150%)",
        section = inputSection,
        position = 4
    )
    @Units(Units.PERCENT)
    default int typingSpeedPercent()
    {
        return 100;
    }

    @ConfigItem(
        keyName = "enableIdleBehavior",
        name = "Enable Idle Behavior",
        description = "Move mouse naturally during idle periods",
        section = inputSection,
        position = 5
    )
    default boolean enableIdleBehavior()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableOvershoot",
        name = "Enable Overshoot",
        description = "Occasionally overshoot mouse targets and correct",
        section = inputSection,
        position = 6
    )
    default boolean enableOvershoot()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableMicroCorrections",
        name = "Enable Micro-Corrections",
        description = "Small mouse adjustments after reaching targets",
        section = inputSection,
        position = 7
    )
    default boolean enableMicroCorrections()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableTypoSimulation",
        name = "Enable Typo Simulation",
        description = "Simulate occasional typing mistakes with correction",
        section = inputSection,
        position = 8
    )
    default boolean enableTypoSimulation()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableFKeyLearning",
        name = "Enable F-Key Learning",
        description = "F-keys become faster with repeated use (muscle memory)",
        section = inputSection,
        position = 9
    )
    default boolean enableFKeyLearning()
    {
        return true;
    }

    // ========================================================================
    // Behavioral Profile Section
    // ========================================================================

    @ConfigSection(
        name = "Behavioral Profile",
        description = "Per-account behavioral fingerprinting settings",
        position = 2,
        closedByDefault = true
    )
    String behavioralSection = "behavioral";

    @ConfigItem(
        keyName = "persistBehavioralProfile",
        name = "Persist Profile",
        description = "Save behavioral profile across sessions",
        section = behavioralSection,
        position = 0
    )
    default boolean persistBehavioralProfile()
    {
        return true;
    }

    @ConfigItem(
        keyName = "encryptBehavioralProfiles",
        name = "Encrypt Profiles",
        description = "Encrypt stored behavioral profiles",
        section = behavioralSection,
        position = 1
    )
    default boolean encryptBehavioralProfiles()
    {
        return false;
    }

    @Range(min = 0, max = 20)
    @ConfigItem(
        keyName = "sessionDriftPercent",
        name = "Session Drift",
        description = "Profile variation between sessions (0-20%)",
        section = behavioralSection,
        position = 2
    )
    @Units(Units.PERCENT)
    default int sessionDriftPercent()
    {
        return 10;
    }

    // ========================================================================
    // Ironman Section
    // ========================================================================

    @ConfigSection(
        name = "Ironman",
        description = "Ironman-specific settings",
        position = 10,
        closedByDefault = true
    )
    String ironmanSection = "ironman";

    @ConfigItem(
        keyName = "ironmanMode",
        name = "Ironman Mode",
        description = "Enable ironman-specific restrictions",
        section = ironmanSection,
        position = 0
    )
    default boolean ironmanMode()
    {
        return false;
    }

    // ========================================================================
    // HCIM Safety Section
    // ========================================================================

    @ConfigSection(
        name = "HCIM Safety",
        description = "Hardcore Ironman safety settings",
        position = 11,
        closedByDefault = true
    )
    String hcimSection = "hcim";

    @ConfigItem(
        keyName = "hcimRequireRingOfLife",
        name = "Require Ring of Life",
        description = "Refuse combat without Ring of Life equipped",
        section = hcimSection,
        position = 0
    )
    default boolean hcimRequireRingOfLife()
    {
        return true;
    }

    @Range(min = 20, max = 70)
    @ConfigItem(
        keyName = "hcimFleeThreshold",
        name = "Flee Threshold",
        description = "HP percentage to trigger escape",
        section = hcimSection,
        position = 1
    )
    @Units(Units.PERCENT)
    default int hcimFleeThreshold()
    {
        return 40;
    }

    // ========================================================================
    // Debug Section
    // ========================================================================

    @ConfigSection(
        name = "Debug",
        description = "Debugging and logging settings",
        position = 99,
        closedByDefault = true
    )
    String debugSection = "debug";

    @ConfigItem(
        keyName = "debugInputLogging",
        name = "Log Input Actions",
        description = "Log detailed mouse/keyboard actions (verbose)",
        section = debugSection,
        position = 0
    )
    default boolean debugInputLogging()
    {
        return false;
    }

    @ConfigItem(
        keyName = "debugTimingStats",
        name = "Log Timing Statistics",
        description = "Log timing distribution statistics",
        section = debugSection,
        position = 1
    )
    default boolean debugTimingStats()
    {
        return false;
    }

    @ConfigItem(
        keyName = "debugClickPositions",
        name = "Log Click Positions",
        description = "Log click position coordinates",
        section = debugSection,
        position = 2
    )
    default boolean debugClickPositions()
    {
        return false;
    }
}
