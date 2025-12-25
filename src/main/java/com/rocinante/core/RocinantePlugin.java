package com.rocinante.core;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.rocinante.config.RocinanteConfig;

/**
 * Main plugin entry point for the Rocinante automation framework.
 *
 * This plugin manages the lifecycle of all automation components and
 * coordinates between the various services (state management, input,
 * timing, task execution).
 *
 * Per REQUIREMENTS.md Section 2.1, this is the single entry point with
 * lifecycle management for the entire framework.
 */
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

    @Inject
    private EventBus eventBus;

    @Inject
    private GameStateService gameStateService;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Rocinante plugin starting...");

        // Register GameStateService with the event bus
        // GameStateService needs to receive events for state tracking
        eventBus.register(gameStateService);

        log.info("Rocinante plugin started - GameStateService registered");
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Rocinante plugin stopping...");

        // Unregister GameStateService from the event bus
        eventBus.unregister(gameStateService);

        // Invalidate all cached state
        gameStateService.invalidateAllCaches();

        log.info("Rocinante plugin stopped - GameStateService unregistered");
    }

    /**
     * Main automation tick handler.
     * This runs on each game tick and will coordinate task execution.
     * Currently a placeholder - task execution will be implemented in Phase 2.
     */
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Main automation tick - task execution will be implemented in Phase 2
        // For now, we just let GameStateService handle state updates via its own subscription
    }

    /**
     * Get the GameStateService for other components.
     * This allows tasks and other services to access game state.
     *
     * @return the game state service
     */
    public GameStateService getGameStateService()
    {
        return gameStateService;
    }

    @Provides
    RocinanteConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RocinanteConfig.class);
    }
}
