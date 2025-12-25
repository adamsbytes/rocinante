package com.rocinante.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("rocinante")
public interface RocinanteConfig extends Config
{
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

    // Ironman settings section
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

    // HCIM Safety section
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

    @ConfigItem(
        keyName = "hcimFleeThreshold",
        name = "Flee Threshold",
        description = "HP percentage to trigger escape",
        section = hcimSection,
        position = 1
    )
    default int hcimFleeThreshold()
    {
        return 40;
    }
}

