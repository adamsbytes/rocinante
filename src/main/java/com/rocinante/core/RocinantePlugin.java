package com.rocinante.core;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.rocinante.config.RocinanteConfig;

@Slf4j
@PluginDescriptor(
    name = "Rocinante",
    description = "Human-like automation framework",
    tags = {"automation", "quests", "combat", "slayer"},
    enabledByDefault = false
)
public class RocinantePlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private RocinanteConfig config;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Rocinante plugin started");
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Rocinante plugin stopped");
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Main automation tick - will be implemented
    }

    @Provides
    RocinanteConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RocinanteConfig.class);
    }
}

