package com.rocinante;

import com.rocinante.core.RocinantePlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Custom launcher for RuneLite with Rocinante plugin sideloaded.
 * 
 * This launcher registers the Rocinante plugin using ExternalPluginManager.loadBuiltin()
 * before starting RuneLite, which allows the plugin to be discovered and loaded
 * without being in the net.runelite.client.plugins package or on the Plugin Hub.
 */
public class RocinanteLauncher {
    public static void main(String[] args) throws Exception {
        // Register Rocinante plugin as a builtin external plugin
        ExternalPluginManager.loadBuiltin(RocinantePlugin.class);
        
        // Start RuneLite normally
        RuneLite.main(args);
    }
}

